package org.openhorizon.exchangeapi.table.apikey

import slick.jdbc.PostgresProfile.api._
import slick.lifted.{TableQuery, Rep, Query}
import java.util.UUID
import org.openhorizon.exchangeapi.table.user.UsersTQ
import scala.concurrent.ExecutionContext

object ApiKeysTQ extends TableQuery(new ApiKeys(_)) { 
  //we are using sha256->bcrypt now so this method is no longer valid
  // def getUsernameByHashedKey(hash: String): Query[Rep[String], String, Seq] =
  //   this.filter(_.hashedKey === hash).map(_.username)
  
  def getByUser(user: UUID): Query[ApiKeys, ApiKeyRow, Seq] =
    this.filter(_.user === user)

  def getById(id: UUID): Query[ApiKeys, ApiKeyRow, Seq] =
    this.filter(_.id === id)

  def insert(apiKey: ApiKeyRow): DBIO[Int] = this += apiKey

  def getByOrg(orgid: String): Query[ApiKeys, ApiKeyRow, Seq] =
    this.filter(_.orgid === orgid)
 
  def getOrgAndUsernameByKeyId(keyId: UUID)(implicit ec: ExecutionContext): DBIO[Option[String]] = {
    val query = for {
    apiKey <- this if apiKey.id === keyId
    user <- UsersTQ if user.user === apiKey.user
  } yield (user.organization, user.username)

    query.result.headOption.map(_.map { case (org, username) => s"$org/$username" })
}
}
