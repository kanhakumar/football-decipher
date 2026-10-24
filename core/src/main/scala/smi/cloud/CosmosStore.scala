package smi.cloud

import cats.effect.IO
import com.azure.cosmos.CosmosClientBuilder
import com.azure.cosmos.models.PartitionKey
import com.azure.identity.DefaultAzureCredentialBuilder
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.circe.Json
import io.circe.syntax.*
import reactor.core.publisher.Mono
import smi.api.MatchStore
import smi.model.*

/** Insights and overlays in Cosmos DB (serverless, NoSQL), written with the managed identity
  * (ADR-016). Append-only per match run: an item per insight version and per overlay operation,
  * partitioned by match id, so a run can be read back for the recap and the eval.
  */
final class CosmosStore(endpoint: String, database: String = "fd") extends MatchStore:
  val name = "cosmos"
  private val client = new CosmosClientBuilder()
    .endpoint(endpoint)
    .credential(new DefaultAzureCredentialBuilder().build())
    .buildAsyncClient()
  private val db = client.getDatabase(database)
  private val insights = db.getContainer("insights")
  private val overlays = db.getContainer("overlays")
  private val mapper = new ObjectMapper()

  def saveInsight(runId: String, i: Insight): IO[Unit] =
    val status = i.status.fold("active")(InsightStatus.wire)
    val id = s"$runId:${i.insightId.value}:$status"
    write(insights.upsertItem(item(i.asJson, id, runId), new PartitionKey(i.matchId.value), null))

  def saveOverlay(runId: String, o: Overlay): IO[Unit] =
    val id = s"$runId:${o.overlayId.value}:${OverlayOp.wire(o.op)}"
    write(overlays.upsertItem(item(o.asJson, id, runId), new PartitionKey(o.matchId.value), null))

  private def item(json: Json, id: String, runId: String): ObjectNode =
    val doc = json.mapObject(_.add("id", id.asJson).add("run_id", runId.asJson))
    mapper.readTree(doc.noSpaces).asInstanceOf[ObjectNode]

  private def write[A](mono: Mono[A]): IO[Unit] =
    IO.fromCompletableFuture(IO(mono.toFuture)).void
