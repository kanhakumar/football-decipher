package smi.cloud

import cats.effect.IO
import com.azure.data.appconfiguration.ConfigurationClientBuilder
import com.azure.data.appconfiguration.models.SettingSelector
import com.azure.identity.DefaultAzureCredentialBuilder
import scala.jdk.CollectionConverters.*
import smi.api.SettingsSource

/** Settings from Azure App Configuration (ADR-019), read with the managed identity. One list call
  * returns every key (unlabelled values only), which keeps the Free tier's daily request limit.
  */
final class AppConfigSource(endpoint: String) extends SettingsSource:
  val name = "App Configuration"

  private lazy val client = ConfigurationClientBuilder()
    .endpoint(endpoint)
    .credential(DefaultAzureCredentialBuilder().build())
    .buildClient()

  def read: IO[Map[String, String]] = IO.blocking {
    client
      .listConfigurationSettings(SettingSelector().setKeyFilter("*"))
      .asScala
      .filter(s => Option(s.getLabel).forall(_.isEmpty))
      .map(s => s.getKey -> Option(s.getValue).getOrElse(""))
      .toMap
  }
