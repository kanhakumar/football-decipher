#!/bin/sh
# Keeps one service API key per environment (ADR-018): the one already in Key Vault, else a new
# random one. azd passes it to Bicep (SMI_API_KEY -> apiKey), which stores it in Key Vault and
# gives it to the apps. The key is never printed or committed.
set -eu
if [ -n "${SMI_API_KEY:-}" ]; then
  exit 0
fi
key=""
if command -v az >/dev/null 2>&1; then
  vault=$(az keyvault list -g "rg-fd-${AZURE_ENV_NAME}" --query "[0].name" -o tsv 2>/dev/null || true)
  if [ -n "$vault" ]; then
    key=$(az keyvault secret show --vault-name "$vault" --name api-key --query value -o tsv 2>/dev/null || true)
  fi
fi
if [ -z "$key" ]; then
  key=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
  echo "Generated a new service API key"
else
  echo "Using the service API key from Key Vault"
fi
azd env set SMI_API_KEY "$key" >/dev/null
