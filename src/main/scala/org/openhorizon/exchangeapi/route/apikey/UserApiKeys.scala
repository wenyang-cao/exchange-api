package org.openhorizon.exchangeapi.route.apikey

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations._
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media._
import jakarta.ws.rs.{GET, POST, DELETE, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.auth._
import org.openhorizon.exchangeapi.table.apikey.{ApiKeyMetadata, ApiKeysTQ, ApiKeyRow}
import org.openhorizon.exchangeapi.table.user.{User, UserRow, UsersTQ}
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange, ResourceChangeRow, ResourceChangesTQ}
import org.openhorizon.exchangeapi.utility._
import java.util.UUID
import slick.jdbc.PostgresProfile.api._
import scala.concurrent.ExecutionContext
import scala.util._
import _root_.org.openhorizon.exchangeapi.utility.ApiKeyUtils
import org.openhorizon.exchangeapi.utility.HttpCode
import io.swagger.v3.oas.annotations.parameters.RequestBody
import scala.concurrent.Future
import scala.util.{Success, Failure}
import java.sql.Timestamp
import java.time.ZoneId

@Path("/v1/orgs/{organization}/users/{username}/apikeys")
@io.swagger.v3.oas.annotations.tags.Tag(name = "apikey")
trait UserApiKeys extends JacksonSupport with AuthenticationSupport {

  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
 
  // === POST /v1/orgs/{organization}/users/{username}/apikeys ===
@POST
@Operation(
  summary = "Create a new API key for a user",
  description = "Creates a new API key for the specified user. Can be called by the user or org admin.",
  parameters = Array(
    new Parameter(name = "organization", in = ParameterIn.PATH, required = true, description = "Organization ID"),
    new Parameter(name = "username", in = ParameterIn.PATH, required = true, description = "Username")
  ),
  requestBody = new RequestBody(
    required = true,
    content = Array(new Content(
      mediaType = "application/json",
      schema = new Schema(implementation = classOf[PostApiKeyRequest]),
      examples = Array(
        new ExampleObject(value = """{
          "description": "Test API key for user"
        }""")
      )
    ))
  ),
  responses = Array(
    new responses.ApiResponse(responseCode = "201", description = "resource created - response body:",
      content = Array(new Content(
        mediaType = "application/json", 
        schema = new Schema(implementation = classOf[PostApiKeyResponse]),
        examples = Array(
          new ExampleObject(value = """{
            "id": "string",
            "description": "string",
            "owner": "string",
            "value": "string",
            "lastUpdated": "string"
          }""")
        )
      ))),
    new responses.ApiResponse(responseCode = "400", description = "bad input"),
    new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
    new responses.ApiResponse(responseCode = "403", description = "access denied")
  )
)
  def postUserApiKey(@Parameter(hidden = true)identity: Identity2, 
                     @Parameter(hidden = true)organization: String, 
                     @Parameter(hidden = true)username: String): Route = {
    entity(as[PostApiKeyRequest]) { body =>
      val ownerStr = s"$organization/$username" //In DB it is UUID. Need to convert to string when displaying to user
      val sha256Token = ApiKeyUtils.generateApiKeyHashedValue()
      val bcryptForDb = ApiKeyUtils.bcryptHash(sha256Token)
      val keyId = ApiKeyUtils.generateApiKeyId()
      val timestamp: java.sql.Timestamp = ApiTime.nowUTCTimestamp //standardizing on Timestamp DB types

      complete {
      db.run((for {
        userOpt <- UsersTQ.filter(u => u.organization === organization && u.username === username).result.headOption
        result <- userOpt match {
          case Some(userRow) =>
            val row = ApiKeyRow(
              orgid = organization,
              id = keyId,
              user = userRow.user, // FK: UUID
              description = body.description,
              hashedKey = bcryptForDb,
              createdAt = timestamp,
              createdBy = identity.identifier.get,
              modifiedAt = timestamp,
              modifiedBy = identity.identifier.get
            )
            for {
              _ <- ApiKeysTQ.insert(row)
              _ <- ResourceChangesTQ += ResourceChange(0L, organization, keyId.toString, ResChangeCategory.APIKEY, public = false, ResChangeResource.APIKEY, ResChangeOperation.CREATED).toResourceChangeRow
            } yield Right(userRow)
          case None =>
            DBIO.successful(Left("User not found."))
        }
      } yield result).transactionally.asTry).map {
          case Success(Right(userRow)) =>
      val response = PostApiKeyResponse(
          id = keyId.toString,
          description = body.description,
          owner = ownerStr, // Display org/username
          value = sha256Token,
          lastUpdated = timestamp.toInstant.atZone(ZoneId.of("UTC")).withZoneSameInstant(ZoneId.of("UTC")).toString)
           (HttpCode.POST_OK, response)

          case Success(Left(msg)) =>
            (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, msg))
          case Failure(_) =>
            (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("apikey.creation.failed")))
        }
      }
    }
  }

  // === DELETE /v1/orgs/{organization}/users/{username}/apikeys/{keyid} ===
  @DELETE
  @Path("/{keyid}")
  @Operation(
  summary = "Delete an API key for a user",
  description = "Deletes API key with the given ID. Must be called by the user themselves (if they are the owner) or an organization admin.",
  parameters = Array(
    new Parameter(name = "organization", in = ParameterIn.PATH, required = true, description = "Organization ID"),
    new Parameter(name = "username", in = ParameterIn.PATH, required = true, description = "Username"),
    new Parameter(name = "keyid", in = ParameterIn.PATH, required = true, description = "API key ID to delete")
  ),
  responses = Array(
    new responses.ApiResponse(responseCode = "204", description = "deleted"),
    new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
    new responses.ApiResponse(responseCode = "403", description = "access denied"),
    new responses.ApiResponse(responseCode = "404", description = "not found"),
    new responses.ApiResponse(responseCode = "500", description = "internal server error")
  )
)
  def deleteUserApiKey(@Parameter(hidden = true)identity: Identity2,
                       @Parameter(hidden = true) organization: String,
                       @Parameter(hidden = true) username: String,
                       @Parameter(hidden = true) keyid: UUID): Route = complete {

   db.run((for {
     deleted <- ApiKeysTQ.getById(keyid).delete
      _ <- if (deleted > 0) ResourceChangesTQ += ResourceChange(0L, organization, keyid.toString, ResChangeCategory.APIKEY, public = false,ResChangeResource.APIKEY, ResChangeOperation.DELETED
       ).toResourceChangeRow else DBIO.successful(0)
    } yield deleted).transactionally.asTry).map {
    case Success(0) =>
        (StatusCodes.NotFound, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("apikey.not.found")))
    case Success(_) =>
         (StatusCodes.NoContent, ApiResponse(ApiRespType.OK, ExchMsg.translate("apikey.deleted")))
    case Failure(ex) =>
      logger.error(s"Error deleting API key $keyid", ex)
       (StatusCodes.InternalServerError, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("apikey.deletion.failed")))
  }
  }

  // === GET /v1/orgs/{organization}/users/{username}/apikeys/{keyid} ===
  @GET
  @Path("/{keyid}")
  @Operation(
  summary = "Get an API key by ID",
  description = "Returns API key with the given ID. Must be called by the user on their own behalf (if they are the owner) or by an organization admin.",
  parameters = Array(
    new Parameter(name = "organization", in = ParameterIn.PATH, required = true, description = "Organization ID"),
    new Parameter(name = "username", in = ParameterIn.PATH, required = true, description = "Username"),
    new Parameter(name = "keyid", in = ParameterIn.PATH, required = true, description = "API key ID")
  ),
  responses = Array(
    new responses.ApiResponse(
      responseCode = "200",
      description = "response body",
      content = Array(new Content(
        mediaType = "application/json",
        schema = new Schema(implementation = classOf[ApiKeyMetadata]),
        examples = Array(
          new ExampleObject(
            value = """{
              "id": "string",
              "description": "string",
              "owner": "string",
              "lastUpdated": "string"
            }"""
          )
        )
      ))
    ),
    new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
    new responses.ApiResponse(responseCode = "403", description = "access denied"),
    new responses.ApiResponse(responseCode = "404", description = "not found")
  )
)
  def getUserApiKeyById(@Parameter(hidden = true)  identity: Identity2,
                        @Parameter(hidden = true)  organization: String,
                        @Parameter(hidden = true)  username: String,
                        @Parameter(hidden = true)  keyid: UUID): Route = complete {

    val userAndKeyQuery = for {
      user <- UsersTQ.filter(u => u.organization === organization && u.username === username)
      key  <- ApiKeysTQ.filter(k => k.id === keyid && k.user === user.user && k.orgid === organization)
    } yield (user, key)

    val fullQuery = for {
      resultOpt   <- userAndKeyQuery.result.headOption
      ownerStrOpt <- resultOpt match {
                        case Some(_) => ApiKeysTQ.getOrgAndUsernameByKeyId(keyid)
                        case None    => DBIO.successful(None)
                      }
    } yield (resultOpt, ownerStrOpt)

    db.run(fullQuery.transactionally).map {
      case (Some((_, keyRow)), Some(ownerStr)) =>
        val metadata = new ApiKeyMetadata(keyRow, ownerStr)
        (StatusCodes.OK, metadata)

      case _ =>
        (StatusCodes.NotFound, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("apikey.not.found")))
    }
  }
def userApiKeys(identity: Identity2): Route = {
  pathPrefix("orgs" / Segment / "users" / Segment / "apikeys") { (organization, username) =>
    val resource = OrgAndId(organization, username).toString
    val resourceType = "user"

    val getResourceIdentity: Future[Option[UUID]] = db.run {
      UsersTQ
        .filter(u => u.organization === organization && u.username === username)
        .map(_.user)
        .result
        .headOption
    }

    def routeMethods(resourceIdentity: Option[UUID]): Route = {
      pathEndOrSingleSlash {
        post {
          exchAuth(TUser(resource, resourceIdentity), Access.WRITE, validIdentity = identity) { _ =>
            postUserApiKey(identity, organization, username)
          }
        }
      } ~
      path(JavaUUID) { keyid =>
        get {
          exchAuth(TUser(resource, resourceIdentity), Access.READ, validIdentity = identity) { _ =>
            getUserApiKeyById(identity, organization, username, keyid)
          }
        } ~
        delete {
          exchAuth(TUser(resource, resourceIdentity), Access.WRITE, validIdentity = identity) { _ =>
            deleteUserApiKey(identity, organization, username, keyid)
          }
        }
      }
    }

    onComplete(getResourceIdentity) {
      case Success(resourceIdentityOpt) => routeMethods(resourceIdentityOpt)
      case Failure(_)                   => routeMethods(None)
    }
  }
}
}