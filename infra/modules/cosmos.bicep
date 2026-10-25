// Serverless NoSQL account with key auth disabled; data access by SQL role assignment.
param location string
param tags object
param token string
param principalId string
@description('Resource ID of the identity; names the role assignments so what-if can show them.')
param identityId string

var containers = [
  { name: 'insights', key: '/match_id' }
  { name: 'overlays', key: '/match_id' }
  { name: 'narrations', key: '/insight_id' }
  { name: 'preferences', key: '/user_id' }
]

resource account 'Microsoft.DocumentDB/databaseAccounts@2024-11-15' = {
  name: 'cosmos-fd-${token}'
  location: location
  tags: tags
  kind: 'GlobalDocumentDB'
  properties: {
    databaseAccountOfferType: 'Standard'
    locations: [{ locationName: location, failoverPriority: 0 }]
    consistencyPolicy: { defaultConsistencyLevel: 'Session' }
    capabilities: [{ name: 'EnableServerless' }]
    disableLocalAuth: true
  }
}

resource database 'Microsoft.DocumentDB/databaseAccounts/sqlDatabases@2024-11-15' = {
  parent: account
  name: 'fd'
  properties: { resource: { id: 'fd' } }
}

resource container 'Microsoft.DocumentDB/databaseAccounts/sqlDatabases/containers@2024-11-15' = [
  for c in containers: {
    parent: database
    name: c.name
    properties: {
      resource: {
        id: c.name
        partitionKey: { paths: [c.key], kind: 'Hash' }
      }
    }
  }
]

// Cosmos DB Built-in Data Contributor
resource dataContributor 'Microsoft.DocumentDB/databaseAccounts/sqlRoleAssignments@2024-11-15' = {
  parent: account
  name: guid(account.id, identityId, 'data-contributor')
  properties: {
    principalId: principalId
    roleDefinitionId: '${account.id}/sqlRoleDefinitions/00000000-0000-0000-0000-000000000002'
    scope: account.id
  }
}

output endpoint string = account.properties.documentEndpoint
