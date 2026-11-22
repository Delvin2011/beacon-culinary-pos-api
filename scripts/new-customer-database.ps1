<#
.SYNOPSIS
    T0-7: sets up a NEW, EMPTY customer database and its first admin (docs/2026-11-22-new-customer-database.md).

.DESCRIPTION
    1. NewDatabaseCommand: the baseline (no demo data), every migration after it, and with -Starter the starter
       template.
    2. FirstAdminCommand (T0-6a): the first admin, without credentials, and their one-time invite link, printed in
       this window only.

    The database connection comes from the environment, never from this script or its arguments:
      SPRING_DATASOURCE_URL       jdbc:sqlserver://<server>:1433;databaseName=<db>;encrypt=true
      SPRING_DATASOURCE_USERNAME  the operator login
      SPRING_DATASOURCE_PASSWORD  if not set, you are asked for it (hidden); it is passed to the two commands
                                  through this process's environment only and cleared at the end.

    The database must have the expected collation (SQL_Latin1_General_CP1_CI_AS unless -Collation says otherwise);
    create it with that collation, as the doc shows. NewDatabaseCommand refuses another one.

    Run it from your own workstation, not from the Azure container's console (whose output is collected into the
    logs). Nothing is printed about the connection.

    The invite link is printed to this window: don't run it with Start-Transcript, and where transcription is
    switched on by policy (the script warns), delete the transcript afterwards. Clear the window when done.

.EXAMPLE
    $env:SPRING_DATASOURCE_URL = 'jdbc:sqlserver://customer.database.windows.net:1433;databaseName=pos;encrypt=true'
    $env:SPRING_DATASOURCE_USERNAME = 'operator'
    .\scripts\new-customer-database.ps1 -Jar .\target\beacon-culinary-api-1.0.0.jar `
        -Name 'Thandi Mokoena' -Email 'thandi@customer.co.za' -BaseUrl 'https://pos.customer.co.za' -Starter
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $Jar,
    [Parameter(Mandatory = $true)] [string] $Name,
    [Parameter(Mandatory = $true)] [string] $Email,
    [string] $BaseUrl,
    [switch] $Starter,
    # The collation the database must have; NewDatabaseCommand's default when empty.
    [string] $Collation
)

$ErrorActionPreference = 'Stop'

function Invoke-OpsCommand([string] $MainClass, [string[]] $CommandArgs) {
    $javaArgs = @('-cp', $Jar, "-Dloader.main=com.beaconculinary.api.ops.$MainClass",
                  'org.springframework.boot.loader.launch.PropertiesLauncher') + $CommandArgs
    # Straight to the console: a function's uncaptured output would otherwise become part of its return value.
    & java @javaArgs | Out-Host
    return $LASTEXITCODE
}

if (-not (Test-Path -LiteralPath $Jar)) { throw "Jar not found: $Jar" }
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'java (17 or later) is not on the PATH.' }
# (Not $name: PowerShell variables ignore case, so it would overwrite -Name.)
foreach ($variable in 'SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME') {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($variable))) { throw "$variable is not set." }
}

# The first admin's invite link is printed to this window: a PowerShell transcript would keep a copy of it.
$transcriptionPolicies = foreach ($hive in 'HKLM:', 'HKCU:') {
    Get-ItemProperty -Path "$hive\SOFTWARE\Policies\Microsoft\Windows\PowerShell\Transcription" -Name EnableTranscripting `
        -ErrorAction SilentlyContinue
}
if ($transcriptionPolicies | Where-Object { $_.EnableTranscripting -eq 1 }) {
    Write-Warning ('PowerShell transcription is switched on by policy: the invite link printed below will be saved in ' +
        'the transcript. Delete that transcript file after the run (docs/2026-11-21-first-admin-and-invites.md).')
}

$askedForPassword = $false
if ([string]::IsNullOrEmpty($env:SPRING_DATASOURCE_PASSWORD)) {
    $secure = Read-Host -AsSecureString -Prompt 'Database password'
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { $env:SPRING_DATASOURCE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
    $askedForPassword = $true
}

try {
    $started = Get-Date
    Write-Host '== 1/2 Database: baseline and migrations' -ForegroundColor Cyan
    $dbArgs = @()
    if ($Starter) { $dbArgs += '--starter' }
    if ($Collation) { $dbArgs += @('--collation', $Collation) }
    $code = Invoke-OpsCommand 'NewDatabaseCommand' $dbArgs
    if ($code -ne 0) {
        Write-Host "Stopped: the database step ended with exit code $code (see above). No admin was created." -ForegroundColor Red
        exit $code
    }

    Write-Host '== 2/2 First admin and their invite link' -ForegroundColor Cyan
    $adminArgs = @('--name', $Name, '--email', $Email)
    if ($BaseUrl) { $adminArgs += @('--base-url', $BaseUrl) }
    $code = Invoke-OpsCommand 'FirstAdminCommand' $adminArgs
    if ($code -ne 0) {
        Write-Host "Stopped: the first-admin step ended with exit code $code (see above). The database is ready;" -ForegroundColor Red
        Write-Host 'run FirstAdminCommand again on its own once the problem is fixed (runbook section 3).' -ForegroundColor Red
        exit $code
    }
    Write-Host ("Done in {0:N1} s. Send the link privately, then remove your firewall rule." -f ((Get-Date) - $started).TotalSeconds) -ForegroundColor Green
    exit 0
}
finally {
    if ($askedForPassword) { Remove-Item Env:SPRING_DATASOURCE_PASSWORD -ErrorAction SilentlyContinue }
}
