package smi.generator

import smi.model.*

/** Fictional clubs. Starters are ordered by formation slot so index 0 is always the goalkeeper. */
final case class Squad(lineup: Lineup):
  def teamId: TeamId = lineup.teamId
  def starters: Vector[Player] = lineup.starters.toVector
  def bench: Vector[Player] = lineup.bench.toVector

object Squads:
  /** 4-3-3 slot anchors in team-relative metres (attacking toward x = 105, right side at low y). */
  val Anchors: Vector[Pt] = Vector(
    Pt(5, 34), // GK
    Pt(28, 8), // RB
    Pt(22, 25), // RCB
    Pt(22, 43), // LCB
    Pt(28, 60), // LB
    Pt(38, 34), // DM
    Pt(48, 22), // RCM
    Pt(48, 46), // LCM
    Pt(66, 10), // RW
    Pt(72, 34), // ST
    Pt(66, 58) // LW
  )
  val Slots = Vector("GK", "RB", "RCB", "LCB", "LB", "DM", "RCM", "LCM", "RW", "ST", "LW")
  val StrikerIndex = 9

  /** Slot groups used to match substitutes: 1-4 defence, 5-7 midfield, 8-10 attack. */
  def group(slot: Int): Int = if slot <= 4 then 0 else if slot <= 7 then 1 else 2

  private val positions = Vector(
    Position.GK,
    Position.RB,
    Position.CB,
    Position.CB,
    Position.LB,
    Position.CDM,
    Position.CM,
    Position.CM,
    Position.RW,
    Position.ST,
    Position.LW
  )

  /** Bench shape: a keeper, two defenders, two midfielders, two forwards. */
  private val benchPositions =
    Vector(
      Position.GK,
      Position.CB,
      Position.RB,
      Position.CM,
      Position.CAM,
      Position.ST,
      Position.LW
    )

  private def squad(
      team: String,
      name: String,
      short: String,
      colors: List[String],
      players: Vector[(Int, String)],
      reserves: Vector[(Int, String)]
  ): Squad =
    val starters = players.zipWithIndex.map { case ((shirt, playerName), i) =>
      Player(
        PlayerId.unsafe(f"P_${team}_$shirt%02d"),
        playerName,
        shirt,
        positions(i),
        Some(Slots(i))
      )
    }
    val bench = reserves.zip(benchPositions).map { case ((shirt, playerName), position) =>
      Player(PlayerId.unsafe(f"P_${team}_$shirt%02d"), playerName, shirt, position)
    }
    Squad(
      Lineup(
        TeamId.unsafe(s"T_$team"),
        name,
        short,
        Some(colors.map(HexColor.unsafe)),
        Formation.unsafe("4-3-3"),
        starters.toList,
        bench.toList
      )
    )

  val home: Squad = squad(
    "HOM",
    "Northgate FC",
    "NOR",
    List("#c8102e", "#ffffff"),
    Vector(
      1 -> "Tomas Brandt",
      2 -> "Luis Ortega",
      4 -> "Ian Mackay",
      5 -> "Dmitri Volkov",
      3 -> "Sam Okoye",
      6 -> "Aarav Nair",
      8 -> "Elias Nord",
      10 -> "Pablo Serrano",
      7 -> "Marcus Rivera",
      9 -> "Kofi Mensah",
      11 -> "Jonas Weber"
    ),
    Vector(
      13 -> "Viktor Hale",
      15 -> "Omar Sato",
      16 -> "Felix Arndt",
      14 -> "Ruben Castell",
      18 -> "Danilo Ferraz",
      19 -> "Kwame Adjei",
      17 -> "Leon Hartwig"
    )
  )

  val away: Squad = squad(
    "AWY",
    "Eastmere Rovers",
    "EAS",
    List("#1d4ed8", "#f8fafc"),
    Vector(
      1 -> "Oskar Lindqvist",
      2 -> "Rafael Duarte",
      5 -> "Callum Reid",
      4 -> "Yusuf Demir",
      3 -> "Theo Marsh",
      6 -> "Hiro Tanabe",
      8 -> "Mateo Ibarra",
      10 -> "Nils Bergman",
      7 -> "Ade Okonjo",
      9 -> "Liam Carver",
      11 -> "Bastian Kroll"
    ),
    Vector(
      12 -> "Erik Solberg",
      15 -> "Marco Venn",
      22 -> "Aiden Pryce",
      14 -> "Sami Haddad",
      16 -> "Joao Teles",
      19 -> "Isak Moreno",
      20 -> "Tariq Bello"
    )
  )
