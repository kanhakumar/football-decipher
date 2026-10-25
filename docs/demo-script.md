# Demo script (under 2 minutes)

The demo match is seed 154, default scenario, at 10x with the suggested delay. Its moments: an away counter-attack goal at 33', a home high press from 55' to 70', a home red card at 76', a ten-man equaliser at 81', and a 2-2 finish. Record the deployed viewer, signed in. Before recording, set `access:sign_in_required` and `match:speed` in App Configuration as you want them. Use 20x to reach late moments faster in takes 3 and 4.

| Time | On screen | Voice-over |
|---|---|---|
| 0:00 to 0:15 | The viewer, pre-match screen with both teams and the countdown | "Live football produces thousands of events, but fans see a score. Football Decipher Live turns a live event stream into match intelligence you can check, in your words and your language. Every club, player and match here is synthetic." |
| 0:15 to 0:45 | Kickoff. Ticker and pitch dots, then the key-pass card, then the counter-attack goal card with the score moving | "Code computes every fact: xG, pressing, shape. Detectors find the moments that matter. A quick verified card appears on time, and the model's line replaces it, saying why it matters: what happened, the pattern, and the match situation." |
| 0:45 to 1:05 | The same moment as Casual, then Analyst (shape map on the pitch), then Language to Hindi and Spanish | "A casual fan gets plain words and the story. An analyst gets xG, PPDA and the team's average positions. Same facts, narrated per viewer, in English, Hindi or Spanish." |
| 1:05 to 1:20 | Persona to Player focus (pick a scorer), then to Club fan for the home side at the red card and the ten-man equaliser | "Follow a player, or your club. The ten-man equaliser reads very differently from each side." |
| 1:20 to 1:40 | A terminal: `narrate --insight ... --model foundry` printing a rejected attempt with its reason, then the verified line. Then `sbt "rawfeed --seed 154"` reporting a correction recovered | "The model never invents a number. A verifier checks every number, name and claim against the engine. A rejected line is retried with the reasons, and falls back to a template. Messy feeds are handled too: late, out-of-order and corrected events." |
| 1:40 to 1:55 | The GitHub Actions Deploy run with the eval gate summary, then App Configuration with `match:speed` | "Every deploy replays this match and checks 165 narrated lines: 100% of numbers must match, and Foundry scores groundedness. Settings change live from Azure." |
| 1:55 to 2:00 | The architecture diagram from the README | "Microsoft Foundry, Agent Framework, MCP, Container Apps, Event Hubs, Cosmos DB, all on Azure. Football Decipher Live." |

## Before recording

- Warm up: open the viewer once and start a short match, so the first card is not slow.
- In a terminal, run `narrate` once beforehand to find an insight whose trail shows a rejection. Any moment where the eval report lists `attempts > 1` works.
- Keep the Deploy run's summary page open in a tab.
- Crop the browser to the broadcast stage for takes 2 to 4.
