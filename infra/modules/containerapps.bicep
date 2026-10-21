// Container Apps environment, the core and agents apps, and the generator job.
// core runs exactly one replica: the match hub keeps its state in memory.
param location string
param tags object
param token string
param workspaceId string
param identityId string
param identityClientId string
param registryServer string
param appInsightsSecretUri string
param coreImage string
param agentsImage string
param corsOrigin string
param eventHubsNamespace string
param cosmosEndpoint string
param projectEndpoint string
param modelDeployment string

var coreName = 'ca-core-${token}'
var identity = {
  type: 'UserAssigned'
  userAssignedIdentities: { '${identityId}': {} }
}
var registries = [{ server: registryServer, identity: identityId }]
var secrets = [{ name: 'appinsights-connection-string', keyVaultUrl: appInsightsSecretUri, identity: identityId }]
var commonEnv = [
  { name: 'AZURE_CLIENT_ID', value: identityClientId }
  { name: 'APPLICATIONINSIGHTS_CONNECTION_STRING', secretRef: 'appinsights-connection-string' }
]
var feedEnv = [
  { name: 'SMI_FEED', value: 'eventhubs' }
  { name: 'SMI_EVENTHUBS_NAMESPACE', value: eventHubsNamespace }
]
// The Application Insights Java agent in the core image (core/Dockerfile) sends its traces.
var javaEnv = [
  { name: 'JAVA_TOOL_OPTIONS', value: '-javaagent:/app/applicationinsights-agent.jar' }
]
var resources = { cpu: json('0.5'), memory: '1Gi' }

resource environment 'Microsoft.App/managedEnvironments@2024-03-01' = {
  name: 'cae-smi-${token}'
  location: location
  tags: tags
  properties: {
    // Logs go to Log Analytics through diagnostic settings, so no workspace key is needed.
    appLogsConfiguration: { destination: 'azure-monitor' }
  }
}

resource environmentLogs 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  scope: environment
  name: 'logs'
  properties: {
    workspaceId: workspaceId
    logs: [{ categoryGroup: 'allLogs', enabled: true }]
  }
}

resource core 'Microsoft.App/containerApps@2024-03-01' = {
  name: coreName
  location: location
  tags: union(tags, { 'azd-service-name': 'core' })
  identity: identity
  properties: {
    managedEnvironmentId: environment.id
    configuration: {
      ingress: {
        external: true
        targetPort: 8080
        transport: 'auto'
      }
      registries: registries
      secrets: secrets
    }
    template: {
      containers: [
        {
          name: 'core'
          image: coreImage
          resources: resources
          env: concat(commonEnv, feedEnv, javaEnv, [
            { name: 'SMI_HOST', value: '0.0.0.0' }
            { name: 'SMI_COSMOS_ENDPOINT', value: cosmosEndpoint }
            { name: 'SMI_CORS_ORIGINS', value: corsOrigin }
          ])
        }
      ]
      scale: { minReplicas: 1, maxReplicas: 1 }
    }
  }
}

resource agents 'Microsoft.App/containerApps@2024-03-01' = {
  name: 'ca-agents-${token}'
  location: location
  tags: union(tags, { 'azd-service-name': 'agents' })
  identity: identity
  properties: {
    managedEnvironmentId: environment.id
    configuration: {
      ingress: {
        external: true
        targetPort: 8090
        transport: 'auto'
      }
      registries: registries
      secrets: secrets
    }
    template: {
      containers: [
        {
          name: 'agents'
          image: agentsImage
          resources: resources
          env: concat(commonEnv, [
            { name: 'SMI_CORE_URL', value: 'http://${coreName}' }
            { name: 'AZURE_AI_PROJECT_ENDPOINT', value: projectEndpoint }
            { name: 'SMI_MODEL_DEPLOYMENT', value: modelDeployment }
            { name: 'SMI_COSMOS_ENDPOINT', value: cosmosEndpoint }
            { name: 'SMI_CORS_ORIGINS', value: corsOrigin }
          ])
        }
      ]
      scale: { minReplicas: 1, maxReplicas: 1 }
    }
  }
}

// Standalone publisher of the demo match into Event Hubs; started by hand.
resource generator 'Microsoft.App/jobs@2024-03-01' = {
  name: 'caj-generator-${token}'
  location: location
  tags: tags
  identity: identity
  properties: {
    environmentId: environment.id
    configuration: {
      triggerType: 'Manual'
      replicaTimeout: 7200
      replicaRetryLimit: 0
      manualTriggerConfig: { parallelism: 1, replicaCompletionCount: 1 }
      registries: registries
      secrets: secrets
    }
    template: {
      containers: [
        {
          name: 'generator'
          image: coreImage
          command: ['java', '-cp', 'app.jar', 'smi.Publish', '--seed', '154', '--speed', '1']
          resources: resources
          env: concat(commonEnv, feedEnv, javaEnv)
        }
      ]
    }
  }
}

output environmentName string = environment.name
output coreUrl string = 'https://${core.properties.configuration.ingress.fqdn}'
output coreInternalUrl string = 'http://${coreName}'
output agentsUrl string = 'https://${agents.properties.configuration.ingress.fqdn}'
output generatorJobName string = generator.name
