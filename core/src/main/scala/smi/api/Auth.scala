package smi.api

import cats.data.Kleisli
import cats.effect.IO
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.{JWKSource, JWKSourceBuilder}
import com.nimbusds.jose.proc.{JWSVerificationKeySelector, SecurityContext}
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.{DefaultJWTClaimsVerifier, DefaultJWTProcessor}
import io.circe.syntax.*
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import org.http4s.*
import org.http4s.circe.jsonEncoder
import org.http4s.headers.Authorization
import org.typelevel.ci.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Who called: a signed-in person (Entra ID access token) or a service (the API key). */
enum Caller:
  case User(id: String)
  case Service

/** Checks Entra ID access tokens for the API (ADR-018): RS256 signature against the Microsoft
  * identity platform's keys, audience, expiry, an issuer that matches the token's own tenant (work,
  * school or personal accounts), and the `access_as_user` scope.
  */
final class EntraTokens(audience: String, keys: JWKSource[SecurityContext]):
  private val processor =
    val p = DefaultJWTProcessor[SecurityContext]()
    p.setJWSKeySelector(JWSVerificationKeySelector(JWSAlgorithm.RS256, keys))
    p.setJWTClaimsSetVerifier(
      DefaultJWTClaimsVerifier[SecurityContext](
        audience,
        JWTClaimsSet.Builder().build(),
        Set("tid", "iss", "exp", "scp").asJava
      )
    )
    p

  /** The caller's stable id (`oid`, else `sub`), or why the token was refused. */
  def verify(token: String): Either[String, String] =
    for
      claims <- Try(processor.process(token, null)).toEither.left.map(e =>
        s"invalid token: ${e.getMessage}"
      )
      tid = claims.getStringClaim("tid")
      _ <- Either.cond(
        claims.getIssuer == s"https://login.microsoftonline.com/$tid/v2.0",
        (),
        "token issuer does not match its tenant"
      )
      scopes = Option(claims.getStringClaim("scp")).toList.flatMap(_.split(" "))
      _ <- Either.cond(scopes.contains(EntraTokens.Scope), (), s"token lacks ${EntraTokens.Scope}")
      id <- Option(claims.getStringClaim("oid"))
        .orElse(Option(claims.getSubject))
        .toRight("no user id")
    yield id

object EntraTokens:
  val Scope = "access_as_user"
  val Keys = "https://login.microsoftonline.com/common/discovery/v2.0/keys"

  /** Keys fetched from the identity platform and cached (refreshed on an unknown key id). */
  def remote(audience: String): EntraTokens =
    EntraTokens(audience, JWKSourceBuilder.create[SecurityContext](URI(Keys).toURL).build())

/** The API's gate (ADR-018). Off locally (`SMI_AUTH` unset), so tests and local runs need nothing.
  * When on, every route but the public ones needs `Authorization: Bearer <Entra token>` or
  * `X-API-Key: <key>`. CORS preflights are answered before this runs (Main wraps it in CORS).
  */
object Auth:
  val Public: Set[String] = Set("/health", "/openapi.yaml")
  val KeyHeader: CIString = ci"X-API-Key"

  def sameKey(sent: String, expected: String): Boolean =
    MessageDigest.isEqual(sent.getBytes(UTF_8), expected.getBytes(UTF_8))

  def caller(
      req: Request[IO],
      apiKey: Option[String],
      tokens: Option[EntraTokens]
  ): Either[String, Caller] =
    val key = req.headers.get(KeyHeader).map(_.head.value)
    val bearer = req.headers.get[Authorization].collect {
      case Authorization(Credentials.Token(AuthScheme.Bearer, t)) => t
    }
    (key, bearer) match
      case (Some(k), _) =>
        Either.cond(apiKey.exists(sameKey(k, _)), Caller.Service, "wrong API key")
      case (None, Some(t)) =>
        tokens.toRight("sign-in is not configured").flatMap(_.verify(t)).map(Caller.User(_))
      case _ => Left("sign in, or send an API key")

  def apply(apiKey: Option[String], tokens: Option[EntraTokens])(app: HttpApp[IO]): HttpApp[IO] =
    Kleisli { req =>
      if Public(req.uri.path.renderString) || req.method == Method.OPTIONS then app(req)
      else
        IO.blocking(caller(req, apiKey, tokens)).flatMap {
          case Right(_) => app(req)
          case Left(why) =>
            IO.pure(
              Response[IO](Status.Unauthorized)
                .withEntity(ErrorView(why).asJson)
                .putHeaders(Header.Raw(ci"WWW-Authenticate", "Bearer"))
            )
        }
    }
