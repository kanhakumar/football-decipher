package smi.cloud

import cats.effect.{IO, Resource}
import com.azure.identity.DefaultAzureCredentialBuilder
import com.azure.messaging.eventhubs.models.{CreateBatchOptions, EventPosition, PartitionEvent}
import com.azure.messaging.eventhubs.{EventData, EventDataBatch, EventHubClientBuilder}
import fs2.Stream
import io.circe.parser.decode
import io.circe.syntax.*
import java.nio.charset.StandardCharsets.UTF_8
import reactor.adapter.JdkFlowAdapter
import smi.api.{BusMessage, MatchBus}
import smi.model.Envelope

/** The match bus on Azure Event Hubs (ADR-016): one hub, one partition so a run keeps its order,
  * authenticated with the managed identity (DefaultAzureCredential; no keys).
  *
  * Each event is one canonical envelope as JSON; the run id, the last-envelope marker and the
  * publishing speed travel as application properties.
  */
final class EventHubsBus(namespace: String, hub: String) extends MatchBus:
  val name = "event-hubs"
  private val Partition = "0"
  private val credential = new DefaultAzureCredentialBuilder().build()
  private def builder = new EventHubClientBuilder().credential(namespace, hub, credential)
  private lazy val producer = builder.buildProducerClient()

  def publish(batch: Vector[BusMessage]): IO[Unit] =
    IO.blocking {
      val options = new CreateBatchOptions().setPartitionId(Partition)
      var current: EventDataBatch = producer.createBatch(options)
      for m <- batch do
        val event = toEvent(m)
        if !current.tryAdd(event) then
          producer.send(current)
          current = producer.createBatch(options)
          if !current.tryAdd(event) then
            throw new IllegalStateException(s"envelope ${m.envelope.seq} is larger than a batch")
      if current.getCount > 0 then producer.send(current)
    }

  def subscribe: Resource[IO, Stream[IO, BusMessage]] =
    Resource
      .make(
        IO.blocking(
          builder
            .consumerGroup(EventHubClientBuilder.DEFAULT_CONSUMER_GROUP_NAME)
            .buildAsyncConsumerClient()
        )
      )(c => IO.blocking(c.close()))
      .map { consumer =>
        val events = consumer.receiveFromPartition(Partition, EventPosition.latest())
        fs2.interop.flow
          .fromPublisher[IO](JdkFlowAdapter.publisherToFlowPublisher(events), 256)
          .map(fromEvent)
          .unNone
      }

  private def toEvent(m: BusMessage): EventData =
    val event = new EventData(m.envelope.asJson.noSpaces.getBytes(UTF_8))
    event.getProperties.put("run_id", m.runId)
    event.getProperties.put("last", java.lang.Boolean.valueOf(m.last))
    event.getProperties.put("speed", java.lang.Double.valueOf(m.speed))
    event

  private def fromEvent(pe: PartitionEvent): Option[BusMessage] =
    val e = pe.getData
    val props = e.getProperties
    for
      runId <- Option(props.get("run_id")).map(_.toString)
      envelope <- decode[Envelope](new String(e.getBody, UTF_8)).toOption
    yield BusMessage(
      runId,
      envelope,
      Option(props.get("last")).exists(_.toString.toBoolean),
      Option(props.get("speed")).flatMap(_.toString.toDoubleOption).getOrElse(1.0)
    )
