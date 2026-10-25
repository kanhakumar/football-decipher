"""Adds the config/app.yaml settings that App Configuration does not have yet (ADR-019).

Values already in the store are never changed, so edits made in the portal survive redeploys.
`access:cors_origins` is seeded with the viewer's address instead of the file's `*`. Uses the az
CLI signed in as the deployer (App Configuration Data Owner); retries while that new role
assignment propagates.

Usage: python seed_settings.py <store name> <path to config/app.yaml> [viewer url]
"""

import json
import shutil
import subprocess
import sys
import time

import yaml

AZ = shutil.which("az") or "az"


def flatten(tree: dict, prefix: str = "") -> dict[str, str]:
    out: dict[str, str] = {}
    for key, value in tree.items():
        name = f"{prefix}:{key}" if prefix else key
        if isinstance(value, dict):
            out |= flatten(value, name)
        else:
            out[name] = str(value).lower() if isinstance(value, bool) else str(value)
    return out


def az(*args: str) -> str:
    command = [AZ, *args, "--auth-mode", "login", "-o", "json"]
    done = subprocess.run(command, capture_output=True, text=True)
    if done.returncode != 0:
        raise RuntimeError(done.stderr.strip() or f"az {' '.join(args)} failed")
    return done.stdout


def existing_keys(store: str, attempts: int = 12, wait_s: float = 15) -> set[str]:
    for attempt in range(1, attempts + 1):
        try:
            keys = az("appconfig", "kv", "list", "-n", store, "--all", "--query", "[].key")
            return set(json.loads(keys))
        except RuntimeError as e:
            if attempt == attempts:
                raise
            print(f"settings store not readable yet ({e.args[0][:100]}); retry in {wait_s:.0f} s")
            time.sleep(wait_s)
    return set()


def main() -> None:
    store, path = sys.argv[1], sys.argv[2]
    viewer = sys.argv[3].rstrip("/") if len(sys.argv) > 3 and sys.argv[3] else ""
    with open(path, encoding="utf-8") as f:
        wanted = flatten(yaml.safe_load(f))
    if viewer:
        wanted["access:cors_origins"] = viewer
    have = existing_keys(store)
    missing = {k: v for k, v in wanted.items() if k not in have}
    for key, value in sorted(missing.items()):
        az("appconfig", "kv", "set", "-n", store, "--key", key, "--value", value, "--yes")
    kept = len(wanted) - len(missing)
    print(f"Settings in {store}: {len(missing)} added, {kept} kept as they were")


if __name__ == "__main__":
    main()
