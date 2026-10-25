package smi

/** The core service's settings, from environment variables (ADR-016). Defaults run it locally. */
object Config:
  private def env(name: String): Option[String] = sys.env.get(name).map(_.trim).filter(_.nonEmpty)

  def host: String = env("SMI_HOST").getOrElse("127.0.0.1")
  def port: Int = env("SMI_PORT").flatMap(_.toIntOption).getOrElse(8080)

  /** Allowed browser origins, comma separated; `*` (the default) allows any. */
  def corsOrigins: List[String] =
    env("SMI_CORS_ORIGINS").getOrElse("*").split(",").map(_.trim).filter(_.nonEmpty).toList

  /** `replay` (default), `memory` (a bus in process) or `eventhubs`. */
  def feed: String = env("SMI_FEED").getOrElse("replay")

  /** Fully qualified Event Hubs namespace (`<name>.servicebus.windows.net`) and hub name. */
  def eventHubs: Option[(String, String)] =
    env("SMI_EVENTHUBS_NAMESPACE").map { ns =>
      val fq = if ns.contains(".") then ns else s"$ns.servicebus.windows.net"
      fq -> env("SMI_EVENTHUB").getOrElse("match-feed")
    }

  def cosmosEndpoint: Option[String] = env("SMI_COSMOS_ENDPOINT")

  /** Sign-in and keys (ADR-018): `SMI_AUTH=on` requires a token or the API key on every route but
    * /health and /openapi.yaml. `SMI_AUTH_AUDIENCE` is the API app's client id (Entra ID tokens are
    * accepted only with it); `SMI_API_KEY` is the service key (from Key Vault in Azure).
    */
  def authOn: Boolean = env("SMI_AUTH").contains("on")
  def authAudience: Option[String] = env("SMI_AUTH_AUDIENCE")
  def apiKey: Option[String] = env("SMI_API_KEY")

  /** Azure App Configuration endpoint for the settings (ADR-019); none locally (config/app.yaml).
    */
  def appConfigEndpoint: Option[String] = env("SMI_APPCONFIG_ENDPOINT")
