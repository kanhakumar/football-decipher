package smi.insight

import smi.model.*

/** Checks that an insight's numbers follow from its evidence events alone (ADR-011). The Verifier
  * relies on this: if facts are right, narration only has to match the facts.
  */
object Rederive:
  def check(insight: Insight, events: EventId => Option[Event]): Either[String, Unit] =
    val ref = insight.insightId.value
    for
      d <- Detectors.byKind
        .get(insight.kind)
        .toRight(s"$ref: no detector for ${InsightKind.wire(insight.kind)}")
      evidence <- insight.evidenceEventIds
        .map(id => events(id).toRight(s"$ref: missing evidence ${id.value}"))
        .partitionMap(identity) match
        case (Nil, es)     => Right(es.toVector)
        case (err :: _, _) => Left(err)
      windows <- d.windowKeys
        .map(k => insight.facts(k).flatMap(_.asNumber).map(k -> _.toDouble).toRight(s"$ref: no $k"))
        .partitionMap(identity) match
        case (Nil, ws)     => Right(ws.toMap)
        case (err :: _, _) => Left(err)
      header = Header(
        insight.kind,
        insight.period,
        insight.teams,
        insight.players.getOrElse(Nil),
        windows
      )
      facts = Moments.factsFor(d, header, evidence)
      _ <- Either.cond(
        facts == insight.facts,
        (),
        s"$ref: facts ${facts.toMap} != ${insight.facts.toMap}"
      )
      _ <- Either.cond(d.why(facts) == insight.whyItMatters, (), s"$ref: reason differs")
      _ <- Either.cond(d.importance(facts) == insight.importance, (), s"$ref: importance differs")
      _ <- paramsAreFacts(insight)
    yield ()

  /** Every number in why_it_matters.params must be a fact with the same key and value. */
  def paramsAreFacts(insight: Insight): Either[String, Unit] =
    val bad = insight.whyItMatters.params.toList.filter((k, v) =>
      v.isNumber && !insight.facts(k).contains(v)
    )
    Either.cond(
      bad.isEmpty,
      (),
      s"${insight.insightId.value}: params not in facts: ${bad.map(_._1)}"
    )
