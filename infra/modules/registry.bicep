// Images are built in the registry by azd (remote build); the apps pull with AcrPull.
param location string
param tags object
param token string
param principalId string
@description('Resource ID of the identity; names the role assignments so what-if can show them.')
param identityId string

var acrPull = '7f951dda-4ed3-4680-a7ca-43fe172d538d'

resource registry 'Microsoft.ContainerRegistry/registries@2023-07-01' = {
  #disable-next-line BCP334 // the token is always 13 characters
  name: 'crfd${token}'
  location: location
  tags: tags
  sku: { name: 'Basic' }
  properties: { adminUserEnabled: false }
}

resource pull 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  scope: registry
  name: guid(registry.id, identityId, acrPull)
  properties: {
    principalId: principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', acrPull)
  }
}

output loginServer string = registry.properties.loginServer
