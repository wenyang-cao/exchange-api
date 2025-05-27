package org.openhorizon.exchangeapi.route.apikey
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.openhorizon.exchangeapi.auth.{Identity, Role}
import org.openhorizon.exchangeapi.table.apikey.{ApiKeyMetadata, ApiKeyRow, ApiKeysTQ}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiUtils, Configuration, DatabaseConnection, ExchMsg}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import scala.concurrent.Await
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import scala.concurrent.duration.{Duration, DurationInt}
import slick.jdbc
import slick.jdbc.PostgresProfile.api._
import org.openhorizon.exchangeapi.auth.{Password, Role}
import scala.concurrent.ExecutionContext.Implicits.global
import _root_.org.openhorizon.exchangeapi.utility.{HttpCode,ApiTime}
import java.sql.Timestamp
import java.util.UUID

class TestGetApiKeyRoute extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT = ("Accept","application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  private val PASSWORD = "password"
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val ROUTE = "/apikeys/"
  private implicit val formats: DefaultFormats.type = DefaultFormats

      private val TESTORGS = Seq(
      OrgRow(
      description = "",
      heartbeatIntervals = "",
      label = "",
      lastUpdated = "",
      limits = "",
      orgId = "testGetUserApiKeyOrg0",
      orgType = "",
      tags = None
    ),
      OrgRow(
      description = "",
      heartbeatIntervals = "",
      label = "",
      lastUpdated = "",
      limits = "",
      orgId = "testGetUserApiKeyOrg1",
      orgType = "",
      tags = None
    )
  )

  private val TESTUSERS = Seq(
    UserRow(
    createdAt = ApiTime.nowUTCTimestamp,
    email = Some("admin0@example.com"),
    identityProvider = "Open Horizon",
    isHubAdmin = false,
    isOrgAdmin = true,
    modifiedAt = ApiTime.nowUTCTimestamp,
    modified_by = None,
    organization = "testGetUserApiKeyOrg0",
    password = Some(Password.hash(PASSWORD)),
    user = UUID.randomUUID(),
    username = "testGetUserApiKeyAdmin0"
  ),
    UserRow(
    createdAt = ApiTime.nowUTCTimestamp,
    email = Some("user0@example.com"),
    identityProvider = "Open Horizon",
    isHubAdmin = false,
    isOrgAdmin = false,
    modifiedAt = ApiTime.nowUTCTimestamp,
    modified_by = None,
    organization = "testGetUserApiKeyOrg0",
    password = Some(Password.hash(PASSWORD)),
    user = UUID.randomUUID(),
    username = "testGetUserApiKeyUser0"
  ),
    UserRow(
    createdAt = ApiTime.nowUTCTimestamp,
    email = Some("user1@example.com"),
    identityProvider = "Open Horizon",
    isHubAdmin = false,
    isOrgAdmin = false,
    modifiedAt = ApiTime.nowUTCTimestamp,
    modified_by = None,
    organization = "testGetUserApiKeyOrg1",
    password = Some(Password.hash(PASSWORD)),
    user = UUID.randomUUID(),
    username = "testGetUserApiKeyUser1"
  )
)
  private val TESTAPIKEYS = Seq(
    ApiKeyRow(
      orgid = "testGetUserApiKeyOrg0",
      id = UUID.randomUUID(),
      user = TESTUSERS(0).user,
      description = "Test API Key 1",
      hashedKey = "hash1",
      createdAt = ApiTime.nowUTCTimestamp,
      createdBy = TESTUSERS(0).user,
      modifiedAt = ApiTime.nowUTCTimestamp,
      modifiedBy = TESTUSERS(0).user
    ),
    ApiKeyRow(
      orgid = "testGetUserApiKeyOrg0",
      id = UUID.randomUUID(),
      user = TESTUSERS(1).user,
      description = "Test API Key 2",
      hashedKey = "hash2",
      createdAt = ApiTime.nowUTCTimestamp,
      createdBy = TESTUSERS(1).user,
      modifiedAt = ApiTime.nowUTCTimestamp,
      modifiedBy = TESTUSERS(1).user
    ),
    ApiKeyRow(
      orgid = "testGetUserApiKeyOrg0",
      id = UUID.randomUUID(),
      user = TESTUSERS(1).user,
      description = "Test API Key 3",
      hashedKey = "hash3",
      createdAt = ApiTime.nowUTCTimestamp,
      createdBy = TESTUSERS(1).user,
      modifiedAt = ApiTime.nowUTCTimestamp,
      modifiedBy = TESTUSERS(1).user
    ),
    ApiKeyRow(
      orgid = "testGetUserApiKeyOrg1",
      id = UUID.randomUUID(),
      user = TESTUSERS(2).user,
      description = "Test API Key 4",
      hashedKey = "hash4",
      createdAt = ApiTime.nowUTCTimestamp,
      createdBy = TESTUSERS(2).user,
      modifiedAt = ApiTime.nowUTCTimestamp,
      modifiedBy = TESTUSERS(2).user
    ),
  )
  

  private val ORGADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode("testGetUserApiKeyOrg0/testGetUserApiKeyAdmin0:password"))
  private val USERAUTH = ("Authorization", "Basic " + ApiUtils.encode("testGetUserApiKeyOrg0/testGetUserApiKeyUser0:password"))


  override def beforeAll(): Unit = {
    val setupAction = DBIO.seq(
      OrgsTQ ++= TESTORGS,
      UsersTQ ++= TESTUSERS,
      ApiKeysTQ ++= TESTAPIKEYS
    ).map(_ => TESTAPIKEYS.length)
    val result = Await.result(DBCONNECTION.run(setupAction.transactionally), AWAITDURATION)
  }

  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.run(
      ApiKeysTQ.filter(_.orgid startsWith "testGetUserApiKeyOrg").delete
    ), AWAITDURATION)
    Await.ready(DBCONNECTION.run(
      UsersTQ.filter(_.username startsWith "testGetUserApiKey").delete
    ), AWAITDURATION)
    Await.ready(DBCONNECTION.run(
      OrgsTQ.filter(_.orgid startsWith "testGetUserApiKeyOrg").delete
    ), AWAITDURATION)
  }



// GET /users/{username}/apikeys/{keyid}
  // User get own key with key id
  test("GET /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + TESTAPIKEYS(1).id + " -- get existing apikey by id") {
    val response = Http(
      URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + TESTAPIKEYS(1).id
    ).headers(ACCEPT).headers(USERAUTH).asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
  }

  // User tried to get non existent key
  test("GET /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + "nonexistentkeyid -- should return 404") {
    val response = Http(
      URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + UUID.randomUUID()
    ).headers(ACCEPT).headers(USERAUTH).asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  // Org admin tries to access another user's API key
  test("GET /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + TESTAPIKEYS(1).id + " -- org admin should be allowed") {
    val response = Http(
      URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + TESTAPIKEYS(1).id
    ).headers(ACCEPT).headers(ORGADMINAUTH).asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
  }

  // User tries to access someone else's API key (same org) -- should fail
  test("GET /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(0).username + ROUTE + TESTAPIKEYS(1).id + " -- user should be forbidden") {
    val response = Http(
      URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(0).username + ROUTE + TESTAPIKEYS(1).id
    ).headers(ACCEPT).headers(USERAUTH).asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  // Org Admin tries to access API key from another org
  test("GET /orgs/" + TESTORGS(1).orgId + "/users/" + TESTUSERS(2).username + ROUTE + TESTAPIKEYS(3).id + " -- cross-org access should be forbidden") {
    val response = Http(
      URL + TESTORGS(1).orgId + "/users/" + TESTUSERS(2).username + ROUTE + TESTAPIKEYS(3).id
    ).headers(ACCEPT).headers(ORGADMINAUTH).asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  // No auth header provided
  test("GET /orgs/" + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + TESTAPIKEYS(1).id + " -- unauthorized access without credentials") {
    val response = Http(
      URL + TESTORGS(0).orgId + "/users/" + TESTUSERS(1).username + ROUTE + TESTAPIKEYS(1).id
    ).headers(ACCEPT).asString

    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.BADCREDS.intValue)
  }


}