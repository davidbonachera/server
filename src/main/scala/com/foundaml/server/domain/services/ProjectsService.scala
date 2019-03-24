package com.foundaml.server.domain.services

import com.foundaml.server.application.controllers.requests.PostProjectRequest
import com.foundaml.server.domain.factories.ProjectFactory
import com.foundaml.server.domain.models._
import com.foundaml.server.domain.models.errors._
import com.foundaml.server.domain.models.features._
import com.foundaml.server.domain.repositories.ProjectsRepository
import com.foundaml.server.infrastructure.logging.IOLazyLogging
import doobie.util.invariant.UnexpectedEnd
import scalaz.zio.{Task, ZIO}

class ProjectsService(
    projectsRepository: ProjectsRepository,
    projectFactory: ProjectFactory
) extends IOLazyLogging {

  val regex = "[0-9a-zA-Z-_]*"
  def validateAlphaNumerical(input: String): List[ProjectError] = {
    if (input.matches(regex)) {
      Nil
    } else {
      List(
        InvalidProjectIdentifier(
          s"$input is not an alphanumerical id. It should satisfy the following regular expression: $regex"
        )
      )
    }
  }

  def validateFeatureClasses(
      featuresConfiguration: FeaturesConfiguration
  ): List[ProjectError] =
    featuresConfiguration match {
      case FeaturesConfiguration(featureConfigurations) =>
        val allowedFeatureClasses = List(
          FloatFeature.featureClass,
          IntFeature.featureClass,
          StringFeature.featureClass,
          FloatVectorFeature.featureClass,
          IntVectorFeature.featureClass,
          StringVectorFeature.featureClass
        )

        featureConfigurations.flatMap { featureConfiguration =>
          if (allowedFeatureClasses.contains(featureConfiguration.featuresType)) {
            None
          } else {
            Some(
              FeaturesConfigurationError(
                s"${featureConfiguration.featuresType} is not an accepted type for feature ${featureConfiguration.name}"
              )
            )
          }
        }
    }

  def validateProject(project: Project): List[ProjectError] = {
    List(
      validateAlphaNumerical(project.id),
      validateFeatureClasses(project.configuration.features)
    ).flatten
  }

  def createEmptyProject(
      id: String,
      name: String,
      problem: ProblemType,
      featuresConfiguration: FeaturesConfiguration,
      labels: Set[String]
  ): ZIO[Any, Throwable, Project] = {
    val project = Project(
      id,
      name,
      ProjectConfiguration(
        problem,
        featuresConfiguration,
        labels
      ),
      Nil,
      NoAlgorithm()
    )
    val errors = validateProject(project)
    for {
      _ <- errors.headOption.fold[Task[Unit]](
        Task.succeed(Unit)
      )(
        err => warnLog(err.getMessage) *> Task.fail(err)
      )
      insertResult <- projectsRepository.insert(project)
      result <- insertResult.fold(
        err => warnLog(err.getMessage) *> Task.fail(err),
        _ => Task.succeed(project)
      )
    } yield result
  }

  def readProject(id: String) =
    projectFactory.get(id).catchAll {
      case UnexpectedEnd => Task.fail(ProjectDoesNotExist(id))
    }

}
