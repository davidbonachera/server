package com.hyperplan.domain.repositories

import cats.data.NonEmptyChain
import doobie._
import doobie.implicits._
import doobie.postgres._
import cats.effect.IO
import cats.implicits._
import com.hyperplan.domain.errors.ProjectError
import com.hyperplan.domain.errors.ProjectError.ProjectAlreadyExistsError
import com.hyperplan.infrastructure.serialization._
import com.hyperplan.domain.models.backends.Backend
import com.hyperplan.domain.models._
import com.hyperplan.infrastructure.logging.IOLogging
import eu.timepit.refined.collection.NonEmpty

class ProjectsRepository(implicit xa: Transactor[IO]) extends IOLogging {

  /*
   * project
   */
  implicit val problemTypeGet: Get[Either[io.circe.Error, ProblemType]] =
    Get[String].map(ProblemTypeSerializer.decodeJson)
  implicit val problemTypePut: Put[ProblemType] =
    Put[String].contramap(ProblemTypeSerializer.encodeJsonString)

  implicit val algorithmPolicyTypeGet
      : Get[Either[io.circe.Error, AlgorithmPolicy]] =
    Get[String].map(AlgorithmPolicySerializer.decodeJson)
  implicit val algorithmPolicyTypePut: Put[AlgorithmPolicy] =
    Put[String].contramap(AlgorithmPolicySerializer.encodeJsonString)

  implicit val featuresConfigurationGet
      : Get[Either[io.circe.Error, FeatureVectorDescriptor]] =
    Get[String].map(FeaturesConfigurationSerializer.decodeJson)

  implicit val featuresConfigurationPut: Put[FeatureVectorDescriptor] =
    Put[String].contramap(FeaturesConfigurationSerializer.encodeJsonNoSpaces)

  implicit val projectConfigurationGet
      : Get[Either[io.circe.Error, ProjectConfiguration]] =
    Get[String].map(ProjectConfigurationSerializer.decodeJson)

  implicit val projectConfigurationPut: Put[ProjectConfiguration] =
    Put[String].contramap(ProjectConfigurationSerializer.encodeJsonString)

  implicit val backendGet: Get[Either[io.circe.Error, Backend]] =
    Get[String].map(BackendSerializer.decodeJson)
  implicit val backendPut: Put[Backend] =
    Put[String].contramap(BackendSerializer.encodeJsonNoSpaces)

  implicit val securityConfigurationGet
      : Get[Either[io.circe.Error, SecurityConfiguration]] =
    Get[String].map(SecurityConfigurationSerializer.decodeJson)
  implicit val securityConfigurationPut: Put[SecurityConfiguration] =
    Put[String].contramap(SecurityConfigurationSerializer.encodeJsonNoSpaces)

  import ProjectsRepository._

  val separator = ";"
  implicit val labelsTypeGet: Get[Set[String]] =
    Get[String].map(_.split(separator).toSet)
  implicit val labelsTypePut: Put[Set[String]] =
    Put[String].contramap(labels => s"${labels.mkString(separator)}")

  implicit val readProjectRow: Read[ProjectRowData] = Read[
    (
        String,
        String,
        String,
        String,
        String,
        String,
        String,
        String
    )
  ].map {
    case (
        projectId,
        projectName,
        problem,
        policy,
        configuration,
        algorithmId,
        backend,
        securityConfiguration
        ) =>
      (
        projectId,
        projectName,
        ProblemTypeSerializer.decodeJson(problem),
        AlgorithmPolicySerializer.decodeJson(policy),
        ProjectConfigurationSerializer.decodeJson(configuration),
        algorithmId,
        BackendSerializer.decodeJson(backend),
        SecurityConfigurationSerializer.decodeJson(securityConfiguration)
      )
  }

  implicit val writes: Write[Algorithm] = Write[
    (
        String,
        Backend,
        String,
        SecurityConfiguration
    )
  ].contramap(
    algorithm =>
      (algorithm.id, algorithm.backend, algorithm.projectId, algorithm.security)
  )

  def transact[T](io: ConnectionIO[T]) =
    io.transact(xa)

  def insertProjectQuery(project: Project) =
    sql"""INSERT INTO projects(
      id,
      name,
      problem,
      algorithm_policy,
      configuration
    ) VALUES(
      ${project.id},
      ${project.name},
      ${project.problem},
      ${project.policy},
      ${project.configuration}
    )""".update

  def insertProject(
      project: Project
  ): ConnectionIO[Either[NonEmptyChain[ProjectError], Project]] =
    insertProjectQuery(project: Project).run
      .attemptSomeSqlState {
        case sqlstate.class23.UNIQUE_VIOLATION =>
          NonEmptyChain(
            ProjectAlreadyExistsError(
              ProjectAlreadyExistsError.message(project.id)
            ): ProjectError
          ).asLeft[Project]
      }
      .flatMap {
        case Right(_) =>
          insertManyAlgorithm(project.algorithms).map(_ => project.asRight)
        case Left(err) =>
          err.pure[ConnectionIO]
      }

  def readProjectQuery(projectId: String): Query0[ProjectRowData] =
    sql"""
      SELECT projects.id, name, problem, algorithm_policy, configuration, algorithms.id, backend, security
      FROM projects
      JOIN algorithms on algorithms.project_id = projects.id
      WHERE projects.id=$projectId
      """
      .query[ProjectsRepository.ProjectRowData]

  def readProject(projectId: String): ConnectionIO[Option[Project]] = {
    readProjectQuery(projectId)
      .to[List]
      .map(_.flatMap(dataToProject).reduceOption(_ |+| _))
  }

  def deleteAlgorithmQuery(
      projectId: String,
      algorithmId: String
  ): doobie.Update0 =
    sql"""DELETE FROM algorithms WHERE project_id = $projectId AND id = $algorithmId""".update

  def deleteAlgorithm(projectId: String, algorithmId: String): IO[Int] =
    deleteAlgorithmQuery(projectId, algorithmId).run
      .transact(xa)

  def readAllProjectsQuery: Query0[ProjectRowData] =
    sql"""
      SELECT projects.id, name, problem, algorithm_policy, configuration, algorithms.id, backend, security
      FROM projects
      INNER JOIN algorithms ON algorithms.project_id = projects.id
      """
      .query[ProjectsRepository.ProjectRowData]

  def readAllProjects =
    readAllProjectsQuery
      .to[List]
      .map(
        _.map(dataToProject)
          .foldLeft(Map.empty[String, Project]) { (acc, elem) =>
            elem match {
              case Some(value) =>
                acc
                  .get(value.id)
                  .fold(
                    acc + (value.id -> value)
                  )(project => acc + (project.id -> (project |+| value)))
              case None => acc
            }
          }
          .values
          .toList
      )

  def updateProjectQuery(project: Project) =
    sql"""
        UPDATE projects SET name=${project.name}, algorithm_policy = ${project.policy}
      """.update

  def updateProject(project: Project) = updateProjectQuery(project).run

  def insertAlgorithmQuery(algorithm: Algorithm): doobie.Update0 =
    sql"""INSERT INTO algorithms(
      id,
      backend,
      project_id,
      security
    ) VALUES(
      ${algorithm.id},
      ${algorithm.backend},
      ${algorithm.projectId},
      ${algorithm.security}
    )""".update

  def insertAlgorithm(algorithm: Algorithm): ConnectionIO[Algorithm] =
    insertAlgorithmQuery(algorithm).run
      .flatMap(_ => algorithm.pure[ConnectionIO])

  def insertManyAlgorithm(algorithms: List[Algorithm]): ConnectionIO[Int] = {
    val sql = """INSERT INTO algorithms(
      id,
      backend,
      project_id,
      security
    ) VALUES(
      ?,?,?,?
    )"""
    Update[Algorithm](sql).updateMany(algorithms)
  }

}

object ProjectsRepository {
  type ProjectRowData = (
      // project
      String,
      String,
      Either[io.circe.Error, ProblemType],
      Either[io.circe.Error, AlgorithmPolicy],
      Either[io.circe.Error, ProjectConfiguration],
      // algorithm
      String,
      Either[io.circe.Error, Backend],
      Either[io.circe.Error, SecurityConfiguration]
  )

  def dataToProject(data: ProjectRowData) = data match {
    case (
        projectId,
        name,
        Right(Classification),
        Right(policy),
        Right(projectConfiguration: ClassificationConfiguration),
        algorithmId,
        Right(backend: Backend),
        Right(securityConfiguration: SecurityConfiguration)
        ) =>
      ClassificationProject(
        projectId,
        name,
        projectConfiguration,
        List(
          Algorithm(
            algorithmId,
            backend,
            projectId,
            securityConfiguration
          )
        ),
        policy
      ).some
    case (
        projectId,
        name,
        Right(Regression),
        Right(policy),
        Right(projectConfiguration: RegressionConfiguration),
        algorithmId,
        Right(backend: Backend),
        Right(securityConfiguration: SecurityConfiguration)
        ) =>
      RegressionProject(
        projectId,
        name,
        projectConfiguration,
        List(
          Algorithm(
            algorithmId,
            backend,
            projectId,
            securityConfiguration
          )
        ),
        policy
      ).some
    case _ =>
      none[Project]
  }

}