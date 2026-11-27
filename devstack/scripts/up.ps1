<#
.SYNOPSIS
    T1-L0: starts the local stack (docs/2026-11-23-local-stack.md).

.DESCRIPTION
    1. Checks Docker, devstack/stack.env and the ports.
    2. Builds the backend jar (skip with -SkipBuild) and starts SQL Server (container mode).
    3. Creates any missing customer database: beaconculinary (empty; the migration command gives it every migration
       and the demo data) and testcustomer (T0-7: baseline, starter template, first admin, whose invite link is
       printed here only).
    4. Starts the backend (single-customer mode, the database named by ACTIVE_CUSTOMER or -Customer), Caddy and the
       frontend, and waits until the backend is healthy.
    5. Checks the address from this machine (hosts file and certificate) and says what to fix if it doesn't work.
    T1-1: between 3 and 4, the platform database (created if missing, migrated) and each customer's own database login,
    and every customer registered in it with its <name>.pos.localhost host and its secret names (idempotent).
    T1-3: multi mode (the default) serves every customer at its own address; after 4, each served customer other than
    beaconculinary gets its smoke account (smoke@<name>.test, SMOKE_PASSWORD) if it has none.
    T1-4: each customer has two database logins (the backend's app login: read, write, execute; the migration login:
    db_owner). After registering them, MigrateCustomersCommand updates every customer's database, and the backend starts
    after it. A customer it couldn't update is reported here and answers 503 until fixed (docs/2026-11-26-customer-migrations.md).

.PARAMETER Customer
    Serve this customer's database for this run, instead of ACTIVE_CUSTOMER in stack.env.

.PARAMETER TenancyMode
    single or multi for this run, instead of TENANCY_MODE in stack.env (default multi). multi: every customer at its
    own address; single: only the active customer (-Customer or ACTIVE_CUSTOMER), the others answer 503 at Caddy.

.PARAMETER Frontend
    container | host | none, instead of FRONTEND_MODE in stack.env. host: run "npm run dev" in the frontend
    repository yourself (hot reload); Caddy sends pages to it.

.EXAMPLE
    .\devstack\scripts\up.ps1
    .\devstack\scripts\up.ps1 -Customer testcustomer -SkipBuild
#>
[CmdletBinding()]
param(
    [string] $Customer,
    [ValidateSet('', 'container', 'host', 'none')] [string] $Frontend = '',
    [ValidateSet('', 'single', 'multi')] [string] $TenancyMode = '',
    [switch] $SkipBuild
)
. (Join-Path $PSScriptRoot '_common.ps1')

$started = Get-Date
$config = Get-StackConfig -Customer $Customer -FrontendMode $Frontend
if ($TenancyMode) { $config.TenancyMode = $TenancyMode }
# T1-1: the platform and customer logins' secrets are checked before anything starts.
Assert-PlatformSettings $config
Assert-OfflineGrantKey $config
foreach ($name in Get-ManagedCustomers) { $null = Get-CustomerSecretValues $config $name }
Assert-Docker

Write-Step 'Checks'
$ourCaddy = Get-RunningContainerId $config 'caddy'
if (-not $ourCaddy) {
    foreach ($port in $config.HttpsPort, $config.HttpPort) {
        if (-not (Test-Port $port)) {
            throw "Port $port on 127.0.0.1 is in use (often IIS, IIS Express or another proxy). Stop that program, or set HTTPS_PORT/HTTP_PORT in devstack/stack.env (troubleshooting in the doc)."
        }
    }
}
if ($config.SqlMode -eq 'container' -and -not (Get-RunningContainerId $config 'sql')) {
    if (-not (Test-Port $config.SqlHostPort)) { throw "Port $($config.SqlHostPort) is in use. Set SQL_HOST_PORT in devstack/stack.env." }
}

# Frontend: the container needs the frontend repository's container file (T1-L0-FE).
$frontendUpstream = 'frontend:3000'
if ($config.FrontendMode -eq 'container') {
    $containerFile = @('Dockerfile', 'Containerfile') | ForEach-Object { Join-Path $config.FrontendRepo $_ } |
        Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (-not $containerFile) {
        Write-Problem "No Dockerfile in $($config.FrontendRepo) (FRONTEND_REPO). Starting without the frontend: /api works, pages answer 502. Use -Frontend host for npm run dev."
        $config.FrontendMode = 'none'
    }
}
# T1-10: the image reports the frontend commit it was built from (check-all compares it with FRONTEND_REPO's HEAD).
$env:POS_APP_BUILD_ID = ''
if ($config.FrontendMode -eq 'container') {
    $env:POS_APP_BUILD_ID = Get-FrontendBuildId $config
    if ($env:POS_APP_BUILD_ID) { Write-Ok "frontend build $($env:POS_APP_BUILD_ID)" }
    else { Write-Problem "$($config.FrontendRepo) isn't a git repository: the frontend image reports ""nogit"", and check-all's build check fails." }
}
if ($config.FrontendMode -eq 'host') { $frontendUpstream = 'host.docker.internal:3000' }
if ($config.FrontendMode -eq 'none') { $frontendUpstream = 'no-frontend.invalid:3000' }
Set-ComposeEnvironment $config $frontendUpstream
Write-Ok "tenancy $($config.TenancyMode), serving $((Get-ServedCustomers $config) -join ', '), SQL $($config.SqlMode), frontend $($config.FrontendMode)"

if (-not $SkipBuild) {
    Write-Step 'Building the backend jar (mvnw package, tests skipped)'
    Push-Location $script:RepoRoot
    try {
        & .\mvnw.cmd -q -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw "mvnw package failed (exit code $LASTEXITCODE)." }
    } finally { Pop-Location }
    Write-Ok 'target\beacon-culinary-api-1.0.0.jar'
} elseif (-not (Test-Path -LiteralPath $script:Jar)) {
    throw "-SkipBuild, but $script:Jar doesn't exist."
}

Write-Step 'SQL Server'
if ($config.SqlMode -eq 'container') {
    Invoke-Compose up -d --wait sql
}
Wait-SqlServer $config
Write-Ok 'answering'

foreach ($name in Get-ManagedCustomers) {
    if (-not (Test-StackDatabase $config $name)) { Initialize-CustomerDatabase $config $name }
}
if (-not (Test-StackDatabase $config $config.ActiveCustomer)) {
    throw "There is no database $($config.ActiveCustomer). Create it with devstack/scripts/new-customer.ps1 $($config.ActiveCustomer)."
}
Write-Ok "databases: $((Get-ManagedCustomers) -join ', ')"

Write-Step "Platform database ($($config.PlatformDatabase)) and the customers' logins"
Initialize-PlatformDatabase $config
foreach ($name in Get-ManagedCustomers) { Register-StackCustomer $config $name }
Write-Ok "registered: $((Get-ManagedCustomers) -join ', ') (tenancy $($config.TenancyMode))"

Write-Step 'Customer databases (MigrateCustomersCommand, before the backend starts)'
$code = Invoke-MigrateCustomers $config
if ($code -eq 0) {
    Write-Ok 'every customer database at the current schema'
} else {
    Write-Problem "Not every customer database was updated (exit code $code, above). The stack starts; a customer that failed answers 503 until its cause is fixed and up.ps1 runs again."
}

Write-Step "Backend (serving $((Get-ServedCustomers $config) -join ', ')), gateway$(if ($config.FrontendMode -eq 'container') { ' and frontend' })"
$services = @('backend', 'caddy')
if ($config.FrontendMode -eq 'container') { $services += 'frontend' }
# --build: the backend image picks up the jar just built. A customer switch changes the backend's settings, so compose recreates it.
Invoke-Compose up -d --build @services
Wait-BackendHealthy $config
Write-Ok 'backend healthy'

Write-Step 'From this machine'
$url = Get-CustomerUrl $config $config.ActiveCustomer
$reachable = $true
foreach ($name in Get-ServedCustomers $config) {
    $customerUrl = Get-CustomerUrl $config $name
    try {
        $response = Invoke-WebRequest -Uri "$customerUrl/api/health" -UseBasicParsing -TimeoutSec 15
        Write-Ok "$customerUrl/api/health -> $($response.StatusCode)"
    } catch {
        $reachable = $false
        Write-Problem "$customerUrl/api/health failed from this machine: $($_.Exception.Message)"
        Write-Problem 'Check the hosts-file lines and that Caddy''s root certificate is trusted (devstack/scripts/status.ps1 says which).'
    }
}

Write-Step 'Smoke accounts'
if ($reachable) {
    foreach ($name in Get-ServedCustomers $config) { Initialize-SmokeAccount $config $name }
} else {
    Write-Problem 'Skipped: a customer address is unreachable from this machine (above).'
}

Write-Host ''
Write-Host ("Up in {0:N0} s." -f ((Get-Date) - $started).TotalSeconds) -ForegroundColor Green
Write-Host "  App:     $url/"
Write-Host "  API:     $url/api/health"
if ($config.FrontendMode -eq 'host') { Write-Host '  Pages come from npm run dev on this machine (port 3000).' }
Write-Host "  Logs:    docker compose -p $($config.StackName) logs -f backend"
Write-Host '  Check:   .\devstack\scripts\smoke.ps1'
