package smi

import cats.effect.IO
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import smi.api.*
import smi.insight.Moments

/** A match fed through the bus plays exactly like a replay (ADR-016). */
class BusSuite extends CatsEffectSuite:
  override val munitIOTimeout = 3.minutes

  private val cfg = MatchConfig(154, speed = 600, delayS = 10)

  private def until(hub: Hub)(done: Playback => Boolean): IO[Playback] =
    hub.snapshot.flatMap(p =>
      if done(p) then IO.pure(p) else IO.sleep(100.millis) >> until(hub)(done)
    )

  private def finished(hub: Hub) = until(hub)(p => p.status == Status.Finished && p.closed)

  test("a match through the in-memory bus gives the same feed, insights and overlays as a replay") {
    val expected = Hub.feed(cfg)
    for
      bus <- InMemoryBus()
      store <- InMemoryStore()
      viaBus <- Hub.resource(bus = Some(bus), store = store).use { hub =>
        hub.start(cfg) >> finished(hub)
      }
      viaReplay <- Hub.resource().use(hub => hub.start(cfg) >> finished(hub))
      _ <- IO.sleep(300.millis) // the store is written off the ticker's path
      stored <- store.insights.get
      storedOverlays <- store.overlays.get
    yield
      assertEquals(viaBus.source, FeedSource.Bus)
      assertEquals(viaBus.feed, expected)
      assertEquals(viaBus.insights, Moments.run(expected).out, "same insights as an offline run")
      assertEquals(viaBus.insights, viaReplay.insights)
      assertEquals(viaBus.overlays.map(_.overlayId), viaReplay.overlays.map(_.overlayId))
      assertEquals(viaBus.viewer, expected.size)
      assertEquals(stored.map(_._2), viaBus.insights, "every insight version is stored, in order")
      assert(stored.forall(_._1 == viaBus.runId))
      assertEquals(storedOverlays.map(_._2), viaBus.overlays)
  }

  test("the viewer trails the live side and never passes what has arrived") {
    for
      bus <- InMemoryBus()
      samples <- Hub.resource(bus = Some(bus)).use { hub =>
        hub.start(cfg.copy(speed = 60)) >>
          (IO.sleep(150.millis) >> hub.snapshot).replicateA(12)
      }
    yield
      val running = samples.filter(p => p.status == Status.Running && p.feed.nonEmpty)
      assert(running.nonEmpty)
      assert(running.forall(p => p.viewer <= p.live && p.live <= p.feed.size))
      assert(running.exists(p => p.viewer < p.live), "the viewer runs behind the live side")
      assert(running.forall(!_.ended), "a 60x match is still arriving")
  }

  test("a run published elsewhere (the generator job) is picked up and played") {
    val feed = Hub.feed(cfg)
    for
      bus <- InMemoryBus()
      p <- Hub.resource(bus = Some(bus)).use { hub =>
        Publisher.run(bus, "job-1", feed, IO.pure(Publisher.Control(600, true, false))) >>
          finished(hub)
      }
    yield
      assertEquals((p.runId, p.generation, p.config.seed), ("job-1", 1L, 154L))
      assertEquals(p.feed, feed)
      assert(p.insights.nonEmpty)
  }

  test("restarting drops the old run's envelopes and stops its publisher") {
    for
      bus <- InMemoryBus()
      p <- Hub.resource(bus = Some(bus)).use { hub =>
        for
          _ <- hub.start(cfg.copy(speed = 30))
          first <- until(hub)(_.feed.size > 50)
          _ <- hub.start(cfg.copy(seed = 42))
          _ <- bus.publish(Vector(BusMessage(first.runId, first.feed(10), last = false, 30)))
          p <- finished(hub)
        yield (first, p)
      }
      (first, second) = p
    yield
      assertNotEquals(second.runId, first.runId)
      assertEquals(second.config.seed, 42L)
      assertEquals(second.feed, Hub.feed(cfg.copy(seed = 42)))
  }

  test("the publisher marks the last envelope and keeps order") {
    val feed = Hub.feed(cfg).take(200)
    for
      bus <- InMemoryBus()
      got <- bus.subscribe.use { messages =>
        Publisher
          .run(bus, "r", feed, IO.pure(Publisher.Control(600, true, false)))
          .background
          .surround(
            messages.takeThrough(!_.last).compile.toVector
          )
      }
    yield
      assertEquals(got.map(_.envelope), feed)
      assertEquals(got.map(_.last), Vector.fill(199)(false) :+ true)
  }
