package smi.insight

import io.circe.JsonObject
import smi.model.*

/** The match situation around a moment, from the subject team's side: the score and the players on
  * the pitch. Derived from the goals and red cards in the evidence (`F.withContext`), so it
  * re-derives like any other fact (ADR-011). It is what turns "a spell of pressing" into "pressing
  * while a goal down with ten men".
  */
object Context:
  import F.*

  val Keys: List[String] = List(
    "ctx_score_for",
    "ctx_score_against",
    "ctx_game_state",
    "ctx_players_for",
    "ctx_players_against"
  )

  def facts(h: Header, evidence: Vector[Event]): JsonObject =
    val goals = evidence.filter(isGoal)
    val reds = evidence.filter(isRed)
    val (scored, conceded) = (goals.count(_.teamId == h.team), goals.count(_.teamId != h.team))
    val state =
      if scored > conceded then "leading" else if scored == conceded then "level" else "trailing"
    obj(
      "ctx_score_for" -> int(scored),
      "ctx_score_against" -> int(conceded),
      "ctx_game_state" -> str(state),
      "ctx_players_for" -> int(11 - reds.count(_.teamId == h.team)),
      "ctx_players_against" -> int(11 - reds.count(_.teamId != h.team))
    )

  def trailing(f: JsonObject): Boolean = strOf(f, "ctx_game_state") == "trailing"
  def leading(f: JsonObject): Boolean = strOf(f, "ctx_game_state") == "leading"
  def shortHanded(f: JsonObject): Boolean =
    f("ctx_players_for").isDefined && intOf(f, "ctx_players_for") < intOf(f, "ctx_players_against")
