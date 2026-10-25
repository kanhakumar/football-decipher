package smi

import cats.effect.IO
import cats.syntax.all.*
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.jwk.{JWKSet, RSAKey}
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import java.util.Date
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import org.typelevel.ci.*
import smi.api.{Auth, EntraTokens}

/** The API gate (ADR-018): tokens and keys accepted and refused, with a key pair made here. */
class AuthSuite extends CatsEffectSuite:
  private val audience = "11111111-2222-3333-4444-555555555555"
  private val tenant = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
  private val key: RSAKey = RSAKeyGenerator(2048).keyID("test-key").generate()
  private val other: RSAKey = RSAKeyGenerator(2048).keyID("test-key").generate()
  private val tokens =
    EntraTokens(audience, ImmutableJWKSet[SecurityContext](JWKSet(key.toPublicJWK)))

  private def token(
      signer: RSAKey = key,
      aud: String = audience,
      tid: String = tenant,
      issuerTenant: String = tenant,
      scope: String = "access_as_user",
      expiresInS: Long = 600
  ): String =
    val claims = JWTClaimsSet
      .Builder()
      .audience(aud)
      .issuer(s"https://login.microsoftonline.com/$issuerTenant/v2.0")
      .claim("tid", tid)
      .claim("oid", "user-1")
      .claim("scp", scope)
      .expirationTime(Date(System.currentTimeMillis + expiresInS * 1000))
      .build()
    val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims)
    jwt.sign(RSASSASigner(signer))
    jwt.serialize

  private val app = Auth(Some("secret-key"), Some(tokens))(
    HttpRoutes.of[IO] { case _ => Ok("in") }.orNotFound
  )

  private def call(path: String, headers: Header.ToRaw*): IO[Status] =
    app.run(Request[IO](uri = Uri.unsafeFromString(path)).putHeaders(headers*)).map(_.status)

  private def bearer(t: String): Header.ToRaw = Authorization(
    Credentials.Token(AuthScheme.Bearer, t)
  )

  test("a valid token or the API key gets in") {
    for
      user <- call("/api/match/state", bearer(token()))
      service <- call("/api/match/state", Header.Raw(ci"X-API-Key", "secret-key"))
    yield assertEquals((user, service), (Status.Ok, Status.Ok))
  }

  test("nothing, a wrong key or a bad token is refused") {
    val bad: List[List[Header.ToRaw]] = List(
      Nil,
      List(Header.Raw(ci"X-API-Key", "nope")),
      List(bearer(token(expiresInS = -60))),
      List(bearer(token(aud = "someone-else"))),
      List(bearer(token(signer = other))),
      List(bearer(token(scope = "User.Read"))),
      List(bearer(token(issuerTenant = "ffffffff-0000-0000-0000-000000000000"))),
      List(bearer("not-a-jwt"))
    )
    bad.traverse(h => call("/api/tools/match_state", h*)).map { statuses =>
      assertEquals(statuses, List.fill(bad.size)(Status.Unauthorized))
    }
  }

  test("health, the spec and CORS preflights stay open") {
    for
      health <- call("/health")
      spec <- call("/openapi.yaml")
      preflight <- app.run(Request[IO](Method.OPTIONS, uri"/api/match/start")).map(_.status)
    yield assertEquals((health, spec, preflight), (Status.Ok, Status.Ok, Status.Ok))
  }

  test("the caller's id is the token's oid") {
    val req = Request[IO]().putHeaders(bearer(token()))
    assertEquals(Auth.caller(req, None, Some(tokens)), Right(smi.api.Caller.User("user-1")))
  }
