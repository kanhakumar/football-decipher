// One hub, one partition so the feed stays in order (13a MatchBus).
param location string
param tags object
param token string
param principalId string
@description('Resource ID of the identity; names the role assignments so what-if can show them.')
param identityId string

var roles = [
  '2b629674-e913-4c01-ae53-ef4638d8f975' // Azure Event Hubs Data Sender
  'a638d3c7-ab3a-418d-83e6-5f17a39d4fde' // Azure Event Hubs Data Receiver
]

resource namespace 'Microsoft.EventHub/namespaces@2024-01-01' = {
  name: 'evhns-smi-${token}'
  location: location
  tags: tags
  sku: { name: 'Basic', tier: 'Basic', capacity: 1 }
  properties: { disableLocalAuth: true }
}

resource hub 'Microsoft.EventHub/namespaces/eventhubs@2024-01-01' = {
  parent: namespace
  name: 'match-feed'
  properties: {
    partitionCount: 1
    messageRetentionInDays: 1
  }
}

resource access 'Microsoft.Authorization/roleAssignments@2022-04-01' = [
  for role in roles: {
    scope: namespace
    name: guid(namespace.id, identityId, role)
    properties: {
      principalId: principalId
      principalType: 'ServicePrincipal'
      roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', role)
    }
  }
]

output namespaceFqdn string = '${namespace.name}.servicebus.windows.net'
