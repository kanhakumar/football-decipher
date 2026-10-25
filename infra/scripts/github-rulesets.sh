#!/bin/sh
# Applies the branch rulesets in infra/github/rulesets (ADR-022): creates each one, or updates the
# ruleset of the same name. Needs `gh auth login` as a repository admin. GitHub enforces rulesets
# on public repositories, or private ones on a paid plan.
set -eu
repo=${1:-$(gh repo view --json nameWithOwner --jq .nameWithOwner)}
dir=$(dirname "$0")/../github/rulesets
for file in "$dir"/*.json; do
  name=$(python -c "import json, sys; print(json.load(open(sys.argv[1]))['name'])" "$file")
  id=$(gh api "repos/$repo/rulesets" --jq ".[] | select(.name == \"$name\") | .id")
  if [ -n "$id" ]; then
    gh api --method PUT "repos/$repo/rulesets/$id" --input "$file" --jq '"updated: " + .name'
  else
    gh api --method POST "repos/$repo/rulesets" --input "$file" --jq '"created: " + .name'
  fi
done
