// Step 13b: everything for one environment in one resource group. No keys or secrets:
// the apps reach every service through one user-assigned managed identity.
targetScope = 'subscription'

@minLength(1)
@maxLength(32)
@description('azd environment name; the resource group is rg-fd-<env>.')
param environmentName string

@description('Region for every resource.')
param location string = 'eastus2'

@description('Model deployment name and model (Global Standard).')
param modelName string = 'gpt-5-mini'
param modelVersion string = '2025-08-07'

@description('Global Standard capacity in thousands of tokens per minute; 13b quota check found a 500 limit in eastus2.')
param modelCapacity int = 100

@description('Images set by azd after the first deploy; empty means the placeholder image.')
param coreImageName string = ''
param agentsImageName string = ''

@description('Who runs azd (you locally, the pipeline in CI): may call the model for smoke tests and the online eval gate.')
param deployerPrincipalId string = ''

@secure()
@description('The service API key (ADR-018), kept by the preprovision hook; stored in Key Vault, given to the apps as a secret.')
param apiKey string = ''

@description('Client id of the fd-api app registration (infra/scripts/entra-apps.sh). With the API key it turns sign-in on.')
param authApiClientId string = ''

@description('Object id of the person who edits the settings in the portal (App Configuration Data Owner).')
param configEditorPrincipalId string = ''

var tags = { 'azd-env-name': environmentName }
var token = toLower(uniqueString(subscription().id, environmentName, location))
// Known before deployment, so role assignment names derived from it show in what-if.
var identityResourceId = resourceId(subscription().subscriptionId, rg.name, 'Microsoft.ManagedIdentity/userAssignedIdentities', 'id-fd-${token}')
var placeholderImage = 'mcr.microsoft.com/azuredocs/containerapps-helloworld:latest'

resource rg 'Microsoft.Resources/resourceGroups@2024-03-01' = {
  name: 'rg-fd-${environmentName}'
  location: location
  tags: tags
}

module monitoring 'modules/monitoring.bicep' = {
  scope: rg
  params: { location: location, tags: tags, token: token }
}

module identity 'modules/identity.bicep' = {
  scope: rg
  params: { location: location, tags: tags, token: token }
}

module keyVault 'modules/keyvault.bicep' = {
  scope: rg
  params: {
    location: location
    tags: tags
    token: token
    appInsightsName: monitoring.outputs.appInsightsName
    principalId: identity.outputs.principalId
    identityId: identityResourceId
    apiKey: apiKey
    deployerPrincipalId: deployerPrincipalId
  }
}

module registry 'modules/registry.bicep' = {
  scope: rg
  params: { location: location, tags: tags, token: token, principalId: identity.outputs.principalId, identityId: identityResourceId }
}

module eventHubs 'modules/eventhubs.bicep' = {
  scope: rg
  params: { location: location, tags: tags, token: token, principalId: identity.outputs.principalId, identityId: identityResourceId }
}

module cosmos 'modules/cosmos.bicep' = {
  scope: rg
  params: { location: location, tags: tags, token: token, principalId: identity.outputs.principalId, identityId: identityResourceId }
}

module foundry 'modules/foundry.bicep' = {
  scope: rg
  params: {
    location: location
    tags: tags
    token: token
    principalId: identity.outputs.principalId
    identityId: identityResourceId
    modelName: modelName
    modelVersion: modelVersion
    modelCapacity: modelCapacity
    deployerPrincipalId: deployerPrincipalId
  }
}

module settings 'modules/appconfig.bicep' = {
  scope: rg
  params: {
    location: location
    tags: tags
    token: token
    principalId: identity.outputs.principalId
    identityId: identityResourceId
    editorPrincipalIds: filter([deployerPrincipalId, configEditorPrincipalId], id => !empty(id))
  }
}

module viewer 'modules/staticwebapp.bicep' = {
  scope: rg
  params: { location: location, tags: tags, token: token }
}

module apps 'modules/containerapps.bicep' = {
  scope: rg
  params: {
    location: location
    tags: tags
    token: token
    workspaceId: monitoring.outputs.workspaceId
    identityId: identityResourceId
    identityClientId: identity.outputs.clientId
    registryServer: registry.outputs.loginServer
    appInsightsSecretUri: keyVault.outputs.appInsightsSecretUri
    coreImage: empty(coreImageName) ? placeholderImage : coreImageName
    agentsImage: empty(agentsImageName) ? placeholderImage : agentsImageName
    corsOrigin: viewer.outputs.url
    eventHubsNamespace: eventHubs.outputs.namespaceFqdn
    cosmosEndpoint: cosmos.outputs.endpoint
    projectEndpoint: foundry.outputs.projectEndpoint
    modelDeployment: foundry.outputs.deploymentName
    apiKey: apiKey
    authApiClientId: authApiClientId
    appConfigEndpoint: settings.outputs.endpoint
  }
  // Using the registry and Key Vault outputs makes the apps wait for those modules,
  // AcrPull and Key Vault Secrets User included.
}

output AZURE_LOCATION string = location
output AZURE_TENANT_ID string = tenant().tenantId
output AZURE_RESOURCE_GROUP string = rg.name
output AZURE_CLIENT_ID string = identity.outputs.clientId
output AZURE_CONTAINER_REGISTRY_ENDPOINT string = registry.outputs.loginServer
output AZURE_CONTAINER_APPS_ENVIRONMENT_NAME string = apps.outputs.environmentName
output AZURE_KEY_VAULT_ENDPOINT string = keyVault.outputs.endpoint
output APPLICATIONINSIGHTS_NAME string = monitoring.outputs.appInsightsName
output CORE_URL string = apps.outputs.coreUrl
output CORE_INTERNAL_URL string = apps.outputs.coreInternalUrl
output AGENTS_URL string = apps.outputs.agentsUrl
output VIEWER_URL string = viewer.outputs.url
output GENERATOR_JOB_NAME string = apps.outputs.generatorJobName
output AZURE_AI_ACCOUNT_ENDPOINT string = foundry.outputs.accountEndpoint
output AZURE_AI_PROJECT_ENDPOINT string = foundry.outputs.projectEndpoint
output AZURE_OPENAI_ENDPOINT string = foundry.outputs.accountEndpoint
output SMI_MODEL_DEPLOYMENT string = foundry.outputs.deploymentName
output SMI_COSMOS_ENDPOINT string = cosmos.outputs.endpoint
output SMI_EVENTHUBS_NAMESPACE string = eventHubs.outputs.namespaceFqdn
output SMI_APPCONFIG_ENDPOINT string = settings.outputs.endpoint
output SMI_APPCONFIG_NAME string = settings.outputs.name
