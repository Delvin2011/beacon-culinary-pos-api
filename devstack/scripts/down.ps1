<#
.SYNOPSIS
    T1-L0: stops the local stack. The databases and Caddy's certificate authority are kept.

.PARAMETER Volumes
    Also deletes the stack's volumes: the SQL Server data (every customer database, container mode) and Caddy's
    certificate authority (its root must then be trusted again). Asks you to type DELETE first.
#>
[CmdletBinding()]
param([switch] $Volumes)
. (Join-Path $PSScriptRoot '_common.ps1')

$config = Get-StackConfig
Assert-Docker
Set-ComposeEnvironment $config 'no-frontend.invalid:3000'
# Every profile, so the SQL and frontend containers stop too whatever the current settings say.
$env:COMPOSE_PROFILES = 'sql,frontend'

if ($Volumes) {
    Write-Host "This deletes every database in the $($config.StackName) SQL container and Caddy's certificate authority." -ForegroundColor Yellow
    $answer = Read-Host 'Type DELETE to go on'
    if ($answer -cne 'DELETE') { Write-Host 'Nothing deleted.'; exit 1 }
    Invoke-Compose down --volumes --remove-orphans
    $state = Join-Path $script:StateDir 'customers.txt'
    if (Test-Path -LiteralPath $state) { Remove-Item -LiteralPath $state }
    Write-Host 'Stopped and deleted. Remove the old Caddy root from Windows (the doc says how) before trusting the new one.' -ForegroundColor Green
} else {
    Invoke-Compose down --remove-orphans
    Write-Host 'Stopped. Data kept; up.ps1 starts it again.' -ForegroundColor Green
}
