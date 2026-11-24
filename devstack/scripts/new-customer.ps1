<#
.SYNOPSIS
    T1-L0: another clean customer database for the multi-tenancy slices (T1-1 to T1-10).

.DESCRIPTION
    Creates database <name> with the collation and sets it up the T0-7 way, as a real new customer: the baseline,
    the starter template and a first admin (admin@<name>.test) whose invite link is printed here only. The name is
    remembered (devstack/.state/customers.txt), so status.ps1 and reset.ps1 include it. T1-1: it gets its own database
    login (SECRET_<NAME>_DB_LOGIN and SECRET_<NAME>_DB_PASSWORD must be in stack.env) and is registered in the platform
    database with <name>.pos.localhost. Until T1-2 and T1-3, the
    backend serves one database: switch to the new one with up.ps1 -Customer <name>.

.EXAMPLE
    .\devstack\scripts\new-customer.ps1 secondcafe
#>
[CmdletBinding()]
param([Parameter(Mandatory = $true, Position = 0)] [string] $Name)
. (Join-Path $PSScriptRoot '_common.ps1')

Assert-CustomerName $Name
$config = Get-StackConfig
Assert-Docker
Set-ComposeEnvironment $config $null
if ($config.SqlMode -eq 'container') { Invoke-Compose up -d --wait sql }
Wait-SqlServer $config

# Its login's secrets first, so nothing is created when they are missing.
$null = Get-CustomerSecretValues $config $Name
Assert-PlatformSettings $config

if (Test-StackDatabase $config $Name) { throw "Database $Name already exists. reset.ps1 -Customer $Name starts it again." }
if (-not (Test-Path -LiteralPath $script:Jar)) { throw "The backend jar is missing ($script:Jar). Run up.ps1 first (it builds it)." }

Initialize-CustomerDatabase $config $Name
Add-ManagedCustomer $Name
Initialize-PlatformDatabase $config
Register-StackCustomer $config $Name

Write-Host ''
Write-Host "Customer $Name is ready." -ForegroundColor Green
Write-Host '  1. Add it to the hosts file (as administrator), on the 127.0.0.1 line:'
Write-Host "       $Name.pos.localhost"
Write-Host "  2. Serve it:  .\devstack\scripts\up.ps1 -Customer $Name   (or ACTIVE_CUSTOMER=$Name in stack.env)"
Write-Host "  3. Open the invite link above at $(Get-CustomerUrl $config $Name) to set the first admin's password."
