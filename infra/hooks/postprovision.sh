#!/bin/sh
# Writes viewer/config.js with the deployed core and agents URLs (azd sets them from the Bicep
# outputs). Query parameters in the viewer still override these.
set -eu
here=$(dirname "$0")
target="${1:-$here/../../viewer/config.js}"
if [ -z "${CORE_URL:-}" ] || [ -z "${AGENTS_URL:-}" ]; then
  echo "CORE_URL and AGENTS_URL must be set (run through azd)" >&2
  exit 1
fi
printf "window.SMI_CONFIG = { core: '%s', agents: '%s' };\n" "$CORE_URL" "$AGENTS_URL" > "$target"
echo "Wrote $target (core $CORE_URL, agents $AGENTS_URL)"
