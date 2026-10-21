// RBAC-mode vault holding the Application Insights connection string; the apps read it
// through the managed identity (Key Vault Secrets User).
param location string
param tags object
param token string
param appInsightsName string
param principalId string
@description('Resource ID of the identity; names the role assignments so what-if can show them.')
param identityId string

var secretsUser = '4633458b-17de-408a-b874-0445c86b69e6'

resource appInsights 'Microsoft.Insights/components@2020-02-02' existing = {
  name: appInsightsName
}

resource vault 'Microsoft.KeyVault/vaults@2023-07-01' = {
  name: 'kv-smi-${token}'
  location: location
  tags: tags
  properties: {
    tenantId: tenant().tenantId
    sku: { family: 'A', name: 'standard' }
    enableRbacAuthorization: true
    enableSoftDelete: true
    softDeleteRetentionInDays: 7
  }
}

resource appInsightsSecret 'Microsoft.KeyVault/vaults/secrets@2023-07-01' = {
  parent: vault
  name: 'appinsights-connection-string'
  properties: { value: appInsights.properties.ConnectionString }
}

resource readSecrets 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  scope: vault
  name: guid(vault.id, identityId, secretsUser)
  properties: {
    principalId: principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', secretsUser)
  }
}

output endpoint string = vault.properties.vaultUri
output appInsightsSecretUri string = appInsightsSecret.properties.secretUri
