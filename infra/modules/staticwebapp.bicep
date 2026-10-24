param location string
param tags object
param token string

resource site 'Microsoft.Web/staticSites@2023-12-01' = {
  name: 'stapp-fd-${token}'
  location: location
  tags: union(tags, { 'azd-service-name': 'viewer' })
  sku: { name: 'Free', tier: 'Free' }
  properties: {}
}

output url string = 'https://${site.properties.defaultHostname}'
