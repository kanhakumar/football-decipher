package smi.raw

import cats.effect.{ExitCode, IO, IOApp}
import io.circe.syntax.*
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import smi.generator.{GeneratorConfig, MatchGenerator, Scenario}
import smi.model.*

/** CLI: `sbt "rawfeed --seed 42 [--feed-seed 1] [--out path] [--live --speed 20]"`.
  *
  * Generates a match, emits it as the raw provider feed, makes the feed messy with the emulator,
  * writes the deliveries as NDJSON and checks that the adapter recovers the clean match. With
  * `--live` it also replays the deliveries through the adapter in real time at the given speed.
  */
object RawFeed extends IOApp:
  private final case class Args(
      seed: Long,
      feedSeed: Long,
      out: Option[Path],
      live: Boolean,
      speed: Double
  )

  private def parse(args: List[String], acc: Args): Either[String, Args] = args match
    case Nil => Right(acc)
    case "--seed" :: v :: rest =>
      v.toLongOption.toRight(s"bad seed '$v'").flatMap(s => parse(rest, acc.copy(seed = s)))
    case "--feed-seed" :: v :: rest =>
      v.toLongOption
        .toRight(s"bad feed seed '$v'")
        .flatMap(s => parse(rest, acc.copy(feedSeed = s)))
    case "--out" :: v :: rest => parse(rest, acc.copy(out = Some(Paths.get(v))))
    case "--live" :: rest     => parse(rest, acc.copy(live = true))
    case "--speed" :: v :: rest =>
      v.toDoubleOption
        .filter(s => s >= 1 && s <= 20)
        .toRight(s"speed '$v' must be 1 to 20")
        .flatMap(s => parse(rest, acc.copy(speed = s)))
    case other :: _ => Left(s"unknown argument '$other'")

  private val usage =
    "usage: rawfeed --seed <long> [--feed-seed <long>] [--out <path>] [--live] [--speed <1-20>]"

  def run(args: List[String]): IO[ExitCode] =
    parse(args, Args(42, 1, None, live = false, speed = 1)) match
      case Left(err) => IO.println(s"$err\n$usage").as(ExitCode(2))
      case Right(a) =>
        val canonical = MatchGenerator.generate(GeneratorConfig(a.seed, Scenario.default))
        RawEmitter().emit(canonical) match
          case Left(err)  => IO.println(s"raw emitter: $err").as(ExitCode.Error)
          case Right(raw) => report(a, canonical, raw)

  private def report(a: Args, canonical: Vector[Envelope], raw: Vector[RawMessage]): IO[ExitCode] =
    val kickoff = canonical.collectFirst { case Envelope(_, _, _, _, _, _, _, Payload.Meta(m)) =>
      m.kickoffUtc.toInstant
    }.get
    val (deliveries, mess) = FeedEmulator.emulate(raw, kickoff, EmulatorConfig(a.feedSeed))
    val (messy, out) = Adapter().run(deliveries.map(_.message))
    val (truth, _) = Adapter().run(FeedEmulator.truth(raw, deliveries, mess))
    val recovered = messy.published == truth.published
    val path = a.out.getOrElse(Paths.get("..", "out", s"raw-${a.seed}.ndjson"))
    val issues = out.issues.groupMapReduce(_.kind)(_ => 1)(_ + _)
    def count(op: Op) = out.envelopes.count(e => e.op == op && e.payloadType == PayloadType.Event)
    val (dropStart, dropEnd) = mess.dropout
    val lines = List(
      s"wrote ${path.toAbsolutePath.normalize} (${deliveries.size} raw messages in delivery order)",
      s"mess: ${mess.delayed} delayed, ${mess.reordered} out of order, ${mess.corrections.size} corrections, " +
        s"${mess.phantoms.size} phantom events, dropout ${dropStart / 1000}-${dropEnd / 1000} s after kickoff",
      s"adapter: ${out.envelopes.size} canonical envelopes (${count(Op.Create)} events, ${count(Op.Update)} updates, " +
        s"${count(Op.Delete)} deletes), issues $issues",
      s"recovered the clean match: ${if recovered then "yes" else "NO"}"
    )
    for
      _ <- IO.blocking {
        Option(path.toAbsolutePath.getParent).foreach(Files.createDirectories(_))
        Files.writeString(
          path,
          deliveries.map(_.message.asJson.noSpaces).mkString("", "\n", "\n"),
          UTF_8
        )
      }
      _ <- IO.println(lines.mkString("\n"))
      _ <- if a.live then live(deliveries, a.speed) else IO.unit
    yield if recovered then ExitCode.Success else ExitCode.Error

  /** Replays the deliveries in real time and prints each canonical event as the adapter publishes
    * it.
    */
  private def live(deliveries: Vector[Delivery], speed: Double): IO[Unit] =
    val adapter = Adapter()
    IO.println(s"live replay at ${speed}x (Ctrl+C to stop)") *>
      FeedEmulator
        .stream(deliveries, speed)
        .evalMapAccumulate(AdapterState()) { (s, m) =>
          val (next, out) = adapter.step(s, m)
          val printed = out.envelopes.collect {
            case Envelope(_, seq, _, _, op, _, _, Payload.Ev(e)) =>
              val (min, sec) = Mapping.toRawClock(e.period, e.periodClockS)
              f"$seq%5d ${Op.wire(op)}%-6s ${EventType.wire(e.`type`)}%-18s ${e.teamId.value} $min%02d:$sec%02d"
          }
          IO.whenA(printed.nonEmpty)(IO.println(printed.mkString("\n"))).as((next, ()))
        }
        .compile
        .drain
