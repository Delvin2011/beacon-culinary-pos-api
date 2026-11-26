<#
.SYNOPSIS
    T1-L0: drops the stack's customer databases and creates them again in their starting state.

.DESCRIPTION
    beaconculinary: every migration with the demo data. testcustomer and the customers made with new-customer.ps1:
    the T0-7 way (baseline, starter template, a new first admin with a new invite link, printed here only).
    T1-1: a full reset also drops and recreates the platform database and registers every customer again; resetting
    one customer registers it again (its database logins' users come back with it). T1-4: then the customer migration
    command updates the reset databases (beaconculinary gets every migration there), before the backend starts.
    Only these databases are touched; any other database on the server is left alone. The backend is stopped while
    it runs and started again at the end. Asks you to type RESET first.

.PARAMETER Customer
    Reset only this customer's database (one of the stack's customers).

.PARAMETER Force
    Don't ask (for scripted checks). Never use it against a server that holds anything you want to keep.
#>
[CmdletBinding()]
param(
    [string] $Customer,
    [switch] $Force
)
. (Join-Path $PSScriptRoot '_common.ps1')

$config = Get-StackConfig
Assert-Docker
Assert-StackNetwork $config
Set-ComposeEnvironment $config $null

$targets = Get-ManagedCustomers
if ($Customer) {
    Assert-CustomerName $Customer
    if ($targets -notcontains $Customer) { throw "$Customer isn't one of the stack's customers ($($targets -join ', '))." }
    $targets = @($Customer)
}

$server = 'the SQL Server container'
if ($config.SqlMode -eq 'host') { $server = "your own SQL Server (localhost:$($config.SqlInstancePort))" }
Assert-PlatformSettings $config
foreach ($name in $targets) { $null = Get-CustomerSecretValues $config $name }
$dropped = @($targets)
if (-not $Customer) { $dropped += $config.PlatformDatabase }
Write-Host "This drops and recreates these databases on ${server}: $($dropped -join ', ')" -ForegroundColor Yellow
if (-not $Force) {
    $answer = Read-Host 'Type RESET to go on'
    if ($answer -cne 'RESET') { Write-Host 'Nothing changed.'; exit 1 }
}

$started = Get-Date
Write-Step 'Stopping the backend'
Invoke-Compose stop backend
if ($config.SqlMode -eq 'container') { Invoke-Compose up -d --wait sql }
Wait-SqlServer $config

foreach ($name in $targets) {
    Write-Step "Dropping $name"
    Remove-StackDatabase $config $name
    Initialize-CustomerDatabase $config $name
}

Write-Step 'Platform database'
if (-not $Customer) {
    Write-Step "Dropping $($config.PlatformDatabase)"
    Remove-PlatformDatabase $config
}
Initialize-PlatformDatabase $config
foreach ($name in $targets) { Register-StackCustomer $config $name }

Write-Step 'Customer databases (MigrateCustomersCommand)'
$migrateArgs = @()
if ($Customer) { $migrateArgs = @('--customer', $Customer) }
$code = Invoke-MigrateCustomers $config $migrateArgs
if ($code -ne 0) { throw "The customer migration command failed (exit code $code, above)." }

Write-Step 'Starting the backend'
Invoke-Compose start backend
Wait-BackendHealthy $config

# T1-3: the reset customers' smoke accounts (their databases are new).
$served = @(Get-ServedCustomers $config)
foreach ($name in $targets) {
    if ($served -contains $name) { Initialize-SmokeAccount $config $name }
}
Write-Host ("Reset done in {0:N0} s: {1}." -f ((Get-Date) - $started).TotalSeconds, ($targets -join ', ')) -ForegroundColor Green
