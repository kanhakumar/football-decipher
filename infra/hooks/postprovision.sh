#!/bin/sh
# Writes web/public/config.js (copied into the build) with the deployed core and agents URLs (azd sets them from the Bicep
# outputs) and, when the app registrations exist (infra/scripts/entra-apps.sh), the sign-in
# settings. Client ids and scopes are public by design; the API key is never written here.
# Query parameters in the viewer still override the URLs.
set -eu
here=$(dirname "$0")
target="${1:-$here/../../web/public/config.js}"
if [ -z "${CORE_URL:-}" ] || [ -z "${AGENTS_URL:-}" ]; then
  echo "CORE_URL and AGENTS_URL must be set (run through azd)" >&2
  exit 1
fi
auth=""
if [ -n "${AUTH_VIEWER_CLIENT_ID:-}" ] && [ -n "${AUTH_API_CLIENT_ID:-}" ]; then
  auth=$(printf ", auth: { clientId: '%s', scope: 'api://%s/access_as_user' }" "$AUTH_VIEWER_CLIENT_ID" "$AUTH_API_CLIENT_ID")
fi
printf "window.SMI_CONFIG = { core: '%s', agents: '%s'%s };\n" "$CORE_URL" "$AGENTS_URL" "$auth" > "$target"
echo "Wrote $target (core $CORE_URL, agents $AGENTS_URL, sign-in $([ -n "$auth" ] && echo on || echo off))"

# Settings (ADR-019): add the config/app.yaml keys the store does not have yet; values edited
# in the portal are kept.
if [ -n "${SMI_APPCONFIG_NAME:-}" ]; then
  python "$here/seed_settings.py" "$SMI_APPCONFIG_NAME" "$here/../../config/app.yaml" "${VIEWER_URL:-}"
fi
