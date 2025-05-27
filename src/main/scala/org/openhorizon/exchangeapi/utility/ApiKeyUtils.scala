package org.openhorizon.exchangeapi.utility
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.security.MessageDigest
import org.mindrot.jbcrypt.BCrypt
import java.net.URLEncoder
object ApiKeyUtils {
  def generateApiKeyHashedValue(): String = {
    var bytes:Array[Byte] = new Array[Byte](32)
    val secureRandom = new SecureRandom()
    secureRandom.nextBytes(bytes)
    val base64 = Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
    val sha256 = MessageDigest.getInstance("SHA-256")
    .digest(base64.getBytes("UTF-8"))
    .map("%02x".format(_)).mkString
    URLEncoder.encode(sha256, "UTF-8")
    
  }

  def bcryptHash(str: String): String = {
    BCrypt.hashpw(str, org.mindrot.jbcrypt.BCrypt.gensalt(10))
}

  def generateApiKeyId(): UUID = UUID.randomUUID()
}
