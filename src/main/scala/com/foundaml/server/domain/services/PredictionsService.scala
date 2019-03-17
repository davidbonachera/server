package com.foundaml.server.domain.services

import java.util.UUID

import com.foundaml.server.domain.FoundaMLConfig
import org.http4s._
import scalaz.zio.{IO, Task}
import scalaz.zio.interop.catz._
import com.foundaml.server.domain.models._
import com.foundaml.server.domain.models.backends._
import com.foundaml.server.domain.models.errors._
import com.foundaml.server.domain.models.features._
import com.foundaml.server.domain.models.labels._
import com.foundaml.server.domain.models.labels.transformers.TensorFlowLabels
import com.foundaml.server.domain.repositories.{
  PredictionsRepository,
  ProjectsRepository
}
import com.foundaml.server.infrastructure.serialization.{
  PredictionSerializer,
  TensorFlowFeaturesSerializer,
  TensorFlowLabelsSerializer
}
import com.foundaml.server.infrastructure.streaming.KinesisService
import org.http4s.client.blaze.BlazeClientBuilder

import scala.concurrent.ExecutionContext

class PredictionsService(
    projectsRepository: ProjectsRepository,
    predictionsRepository: PredictionsRepository,
    kinesisService: KinesisService,
    config: FoundaMLConfig
) {

  def persistPrediction(prediction: Prediction) =
    predictionsRepository.insert(prediction)

  def publishPredictionToKinesis(prediction: Prediction) =
    if (config.kinesis.enabled) {
      kinesisService.put(
        prediction,
        config.kinesis.predictionsStream,
        prediction.projectId
      )(PredictionSerializer.encoder)
    } else Task.unit

  def noAlgorithm(): Task[Prediction] =
    Task(println("No algorithm setup")).flatMap { _ =>
      Task.fail(
        NoAlgorithmAvailable("No algorithms are setup")
      )
    }

  def predictWithProjectPolicy(
      features: Features,
      project: Project
  ): Task[Prediction] =
    project.policy
      .take()
      .fold(
        noAlgorithm()
      ) { algorithmId =>
        project.algorithmsMap
          .get(algorithmId)
          .fold(
            noAlgorithm()
          )(
            algorithm =>
              predictWithAlgorithm(
                project.id,
                algorithm,
                features
              )
          )
      }

  def predictWithLocalBackend(
      projectId: String,
      algorithm: Algorithm,
      features: Features,
      local: Local
  ) = {
    Task.succeed(
      Prediction(
        UUID.randomUUID().toString,
        projectId,
        algorithm.id,
        features,
        local.computed,
        Examples(None)
      )
    )
  }
  def predictWithTensorFlowBackend(
      projectId: String,
      algorithm: Algorithm,
      features: Features,
      backend: TensorFlowBackend
  ) = {
    backend.featuresTransformer
      .transform(features)
      .fold(
        err =>
          Task(println(err.getMessage)) *>
            Task.fail(
              FeaturesTransformerError(
                "The features could not be transformed to a TensorFlow compatible format"
              )
            ),
        tfFeatures => {
          implicit val encoder
              : EntityEncoder[Task, TensorFlowClassificationFeatures] =
            TensorFlowFeaturesSerializer.entityEncoder
          val uriString = s"http://${backend.host}:${backend.port}"
          Uri
            .fromString(uriString)
            .fold(
              _ =>
                Task.fail(
                  InvalidArgument(
                    s"The following uri could not be parsed, check your backend configuration. $uriString"
                  )
                ),
              uri => {
                val request =
                  Request[Task](method = Method.POST, uri = uri)
                    .withBody(tfFeatures)
                BlazeClientBuilder[Task](ExecutionContext.global).resource
                  .use(
                    _.expect[TensorFlowLabels](request)(
                      TensorFlowLabelsSerializer.entityDecoder
                    ).flatMap { tfLabels =>
                      backend.labelsTransformer
                        .transform(tfLabels)
                        .fold(
                          err => Task.fail(err),
                          labels =>
                            Task.succeed(
                              Prediction(
                                UUID.randomUUID().toString,
                                projectId,
                                algorithm.id,
                                features,
                                labels,
                                Examples(None)
                              )
                            )
                        )
                    }
                  )
              }
            )

        }
      )
  }

  def predictWithAlgorithm(
      projectId: String,
      algorithm: Algorithm,
      features: Features
  ): Task[Prediction] = {
    val predictionTask = algorithm.backend match {
      case local: Local =>
        predictWithLocalBackend(projectId, algorithm, features, local)
      case tfBackend: TensorFlowBackend =>
        predictWithTensorFlowBackend(projectId, algorithm, features, tfBackend)
    }
    predictionTask.flatMap { prediction =>
      persistPrediction(prediction) *> publishPredictionToKinesis(prediction) *> Task
        .succeed(prediction)
    }
  }

  def validateFeatures(
      expectedFeaturesClass: String,
      expectedFeaturesSize: Int,
      features: Features
  ): Boolean = {
    lazy val typeCheck = expectedFeaturesClass match {
      case DoubleFeatures.featuresClass =>
        features.features.count(_.isInstanceOf[Double]) == features.features.size
      case FloatFeatures.featuresClass =>
        features.features.count(_.isInstanceOf[Float]) == features.features.size
      case IntFeatures.featuresClass =>
        features.features.count(_.isInstanceOf[Int]) == features.features.size
      case StringFeatures.featuresClass =>
        features.features.count(_.isInstanceOf[String]) == features.features.size
      case CustomFeatures.featuresClass =>
        // custom features does not guarantee the features to be correct
        true
    }
    lazy val sizeCheck = features.features.size == expectedFeaturesSize

    sizeCheck && typeCheck
  }

  def validateLabels(
      expectedLabelsClass: Set[String],
      labels: Labels
  ): Boolean = {
    expectedLabelsClass == labels.labels.map(_.label)
  }

  def predict(
      features: Features,
      project: Project,
      optionalAlgorithmId: Option[String]
  ) = {
    if (validateFeatures(
        project.configuration.featureClass,
        project.configuration.featuresSize,
        features
      )) {
      optionalAlgorithmId.fold(
        predictWithProjectPolicy(features, project)
      )(
        algorithmId =>
          project.algorithmsMap
            .get(algorithmId)
            .fold[Task[Prediction]](
              Task(
                println(
                  s"project algorithms: ${project.algorithmsMap.toString()}"
                )
              ) *> Task.fail(
                InvalidArgument(
                  s"The algorithm $algorithmId does not exist in the project ${project.id}"
                )
              )
            )(
              algorithm =>
                predictWithAlgorithm(project.id, algorithm, features).flatMap {
                  prediction =>
                    if (validateLabels(
                        project.configuration.labels,
                        prediction.labels
                      )) {
                      Task.succeed(prediction)
                    } else {
                      Task.fail(
                        LabelsValidationFailed(
                          "The labels do not match the project configuration"
                        )
                      )
                    }
                }
            )
      )
    } else {
      Task.fail(
        FeaturesValidationFailed(
          "The features are not correct for this project"
        )
      )
    }
  }
}