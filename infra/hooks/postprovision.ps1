# Writes viewer/config.js with the deployed core and agents URLs (azd sets them from the
# Bicep outputs). Query parameters in the viewer still override these.
param([string]$Path = (Join-Path $PSScriptRoot '..\..\viewer\config.js'))

$core = $env:CORE_URL
$agents = $env:AGENTS_URL
if (-not $core -or -not $agents) { throw 'CORE_URL and AGENTS_URL must be set (run through azd).' }

$js = "window.SMI_CONFIG = { core: '$core', agents: '$agents' };`n"
[System.IO.File]::WriteAllText($Path, $js)
Write-Host "Wrote $Path (core $core, agents $agents)"
