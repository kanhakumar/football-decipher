#!/bin/sh
# Creates the two Microsoft Entra ID app registrations for sign-in (ADR-018), once per directory.
# Run it yourself after `az login` (it needs the right to register apps, which the deploy pipeline
# does not have):
#
#   sh infra/scripts/entra-apps.sh https://<viewer host>
#
#   fd-api     the API: exposes the scope access_as_user; tokens for it reach the core and agents.
#   fd-viewer  the viewer (single-page app): signs people in and asks for that scope.
#
# Both accept work, school and personal Microsoft accounts. Nothing secret is created: a SPA has no
# client secret. The client ids go to the azd environment and to GitHub variables (gh), where the
# Deploy workflow picks them up. Safe to run again: existing registrations are reused and updated.
# AZ=<path to az> if az is not on PATH (Windows: ".../Azure/CLI2/wbin/az.cmd").
set -eu
viewer_url="${1:?usage: entra-apps.sh <viewer url, e.g. https://example.azurestaticapps.net>}"
viewer_url="${viewer_url%/}"
az="${AZ:-az}"
graph=https://graph.microsoft.com/v1.0
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

uuid() {
  od -An -N16 -tx1 /dev/urandom | tr -d ' \n' |
    sed -E 's/^(.{8})(.{4}).(.{3}).(.{3})(.{12})$/\1-\2-4\3-a\4-\5/'
}

# ---- fd-api ----
api_id=$("$az" ad app list --display-name fd-api --query "[0].appId" -o tsv)
if [ -z "$api_id" ]; then
  scope_id=$(uuid)
  cat > "$work/api.json" <<EOF
{
  "displayName": "fd-api",
  "signInAudience": "AzureADandPersonalMicrosoftAccount",
  "api": {
    "requestedAccessTokenVersion": 2,
    "oauth2PermissionScopes": [{
      "id": "$scope_id",
      "value": "access_as_user",
      "type": "User",
      "isEnabled": true,
      "adminConsentDisplayName": "Watch Football Decipher",
      "adminConsentDescription": "Lets the viewer call the match and narration APIs as the signed-in user.",
      "userConsentDisplayName": "Watch Football Decipher",
      "userConsentDescription": "Lets the viewer call the match and narration APIs as you."
    }]
  }
}
EOF
  api_id=$("$az" rest --method POST --url "$graph/applications" --body "@$work/api.json" --query appId -o tsv)
  echo "Created fd-api ($api_id)"
else
  echo "Found fd-api ($api_id)"
fi
api_object=$("$az" ad app show --id "$api_id" --query id -o tsv)
scope_id=$("$az" ad app show --id "$api_id" --query "api.oauth2PermissionScopes[?value=='access_as_user'].id | [0]" -o tsv)
printf '{ "identifierUris": ["api://%s"] }\n' "$api_id" > "$work/uri.json"
"$az" rest --method PATCH --url "$graph/applications/$api_object" --body "@$work/uri.json"
"$az" ad sp show --id "$api_id" >/dev/null 2>&1 || "$az" ad sp create --id "$api_id" >/dev/null

# ---- fd-viewer ----
cat > "$work/viewer.json" <<EOF
{
  "displayName": "fd-viewer",
  "signInAudience": "AzureADandPersonalMicrosoftAccount",
  "api": { "requestedAccessTokenVersion": 2 },
  "spa": { "redirectUris": ["$viewer_url/", "http://localhost:5500/", "http://127.0.0.1:5500/"] },
  "requiredResourceAccess": [{
    "resourceAppId": "$api_id",
    "resourceAccess": [{ "id": "$scope_id", "type": "Scope" }]
  }]
}
EOF
viewer_id=$("$az" ad app list --display-name fd-viewer --query "[0].appId" -o tsv)
if [ -z "$viewer_id" ]; then
  viewer_id=$("$az" rest --method POST --url "$graph/applications" --body "@$work/viewer.json" --query appId -o tsv)
  echo "Created fd-viewer ($viewer_id)"
else
  viewer_object=$("$az" ad app show --id "$viewer_id" --query id -o tsv)
  "$az" rest --method PATCH --url "$graph/applications/$viewer_object" --body "@$work/viewer.json"
  echo "Updated fd-viewer ($viewer_id)"
fi
"$az" ad sp show --id "$viewer_id" >/dev/null 2>&1 || "$az" ad sp create --id "$viewer_id" >/dev/null

# ---- hand the ids to azd and to the Deploy workflow ----
if command -v azd >/dev/null 2>&1; then
  azd env set AUTH_API_CLIENT_ID "$api_id"
  azd env set AUTH_VIEWER_CLIENT_ID "$viewer_id"
fi
if command -v gh >/dev/null 2>&1; then
  gh variable set AUTH_API_CLIENT_ID --body "$api_id"
  gh variable set AUTH_VIEWER_CLIENT_ID --body "$viewer_id"
fi
echo "AUTH_API_CLIENT_ID=$api_id"
echo "AUTH_VIEWER_CLIENT_ID=$viewer_id"
