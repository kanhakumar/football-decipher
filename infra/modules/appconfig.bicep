// App Configuration store for the settings you change while the demo runs (ADR-019). Free tier
// (1,000 requests a day: only the core reads it, every 2 minutes). Keys and secrets are off: the
// core reads with the managed identity, people and the deploy pipeline edit with their Entra roles.
param location string
param tags object
param token string
param principalId string
@description('Resource ID of the identity; names the role assignment so what-if can show it.')
param identityId string
@description('Who may write the settings: the deploy pipeline (seeding) and the person who edits them in the portal.')
param editorPrincipalIds array = []

var dataReader = '516239f1-63e1-4d78-a4de-a74fb236a071'
var dataOwner = '5ae67dd6-50cb-40e7-96ff-dc2bfa4b606b'

resource store 'Microsoft.AppConfiguration/configurationStores@2024-05-01' = {
  name: 'appcs-fd-${token}'
  location: location
  tags: tags
  sku: { name: 'free' }
  properties: {
    disableLocalAuth: true
  }
}

resource appReads 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  scope: store
  name: guid(store.id, identityId, dataReader)
  properties: {
    principalId: principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', dataReader)
  }
}

resource editors 'Microsoft.Authorization/roleAssignments@2022-04-01' = [
  for id in editorPrincipalIds: {
    scope: store
    name: guid(store.id, id, dataOwner)
    properties: {
      principalId: id
      roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', dataOwner)
    }
  }
]

output endpoint string = store.properties.endpoint
output name string = store.name
