# Synthetic Match Intelligence Engine

An AI pipeline that turns a stream of synthetic football events into explainable match intelligence: live overlays, narratives and a full-time recap, personalised by persona, favourite club or player, metric and language (English, Hindi, Spanish).

All data is synthetic. All clubs and players are fictional.

> Work in progress. Architecture, setup and deployment docs will follow.

## Layout

| Folder    | Contents                                                        |
|-----------|-----------------------------------------------------------------|
| `schema`  | JSON Schemas (source of truth), examples and a validator script |
| `core`    | Scala 3: generator, feed emulator, adapter, engine, API         |
| `agents`  | Python: agent workflow and MCP server                           |
| `viewer`  | Broadcast-style web viewer                                      |
| `infra`   | Infrastructure as code                                          |
| `eval`    | Recorded matches and evaluation checks                          |
| `docs`    | Architecture and decisions                                      |

## Quick checks

```bash
python schema/validate_examples.py   # needs: pip install -r schema/requirements.txt
cd core && sbt test
cd agents && pip install -e ".[dev]" && pytest && ruff check .
```
