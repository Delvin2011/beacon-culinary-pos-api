<#
    T1-L0: shared by the devstack scripts (dot-sourced). Reads devstack/stack.env without printing it, works out the
    values compose needs, and runs sqlcmd and the migration command in containers, so the host needs only Docker, Java 17
    and PowerShell. Passwords reach those containers through environment variables named on the command line
    (docker run -e NAME, value from this process), never as arguments, and are removed from this process afterwards.
    Works in Windows PowerShell 5.1 and PowerShell 7.
#>
Set-StrictMode -Version 2
$ErrorActionPreference = 'Stop'

$script:DevstackRoot = Split-Path -Parent $PSScriptRoot
$script:RepoRoot = Split-Path -Parent $script:DevstackRoot
$script:StackEnvFile = Join-Path $script:DevstackRoot 'stack.env'
$script:StateDir = Join-Path $script:DevstackRoot '.state'
$script:ComposeFile = Join-Path $script:DevstackRoot 'compose.yaml'
$script:Jar = Join-Path $script:RepoRoot 'target\beacon-culinary-api-1.0.0.jar'

# Pinned images (also in compose.yaml for sql). T1-4: the Java image is the backend Dockerfile's base; the customer
# migration command runs in it, on the stack network.
$script:SqlImage = 'mcr.microsoft.com/mssql/server:2022-CU16-ubuntu-22.04'
$script:JavaImage = 'eclipse-temurin:17-jre-jammy'
$script:Collation = 'SQL_Latin1_General_CP1_CI_AS'

# The two databases every stack has; new-customer.ps1 adds more (recorded in .state/customers.txt).
$script:FullHistoryCustomer = 'beaconculinary'
$script:CleanCustomer = 'testcustomer'
$script:CustomerNamePattern = '^[a-z][a-z0-9]{2,29}$'
$script:ReservedNames = @('master', 'model', 'msdb', 'tempdb', 'sql', 'backend', 'frontend', 'caddy', 'api', 'www')
# T1-1: the platform database's own login (its password is PLATFORM_DB_PASSWORD).
$script:PlatformLogin = 'pos_platform'

function Write-Step([string] $Text) { Write-Host "== $Text" -ForegroundColor Cyan }
function Write-Ok([string] $Text) { Write-Host "   ok  $Text" -ForegroundColor Green }
function Write-Problem([string] $Text) { Write-Host "   !!  $Text" -ForegroundColor Yellow }

function Assert-CustomerName([string] $Name) {
    if ($Name -cnotmatch $script:CustomerNamePattern) {
        throw "Customer name '$Name' must be 3-30 lower-case letters and digits, starting with a letter (it becomes <name>.pos.localhost and the database name)."
    }
    if ($script:ReservedNames -contains $Name) { throw "Customer name '$Name' is reserved." }
}

<# stack.env as a hashtable. Comments and blank values are left out, so a blank line takes its default. #>
function Read-StackEnv {
    if (-not (Test-Path -LiteralPath $script:StackEnvFile)) {
        throw "devstack/stack.env is missing. Copy devstack/stack.env.example to devstack/stack.env and fill it in (docs/2026-11-23-local-stack.md, first-time set-up)."
    }
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $script:StackEnvFile) {
        $trimmed = $line.Trim()
        if ($trimmed -eq '' -or $trimmed.StartsWith('#')) { continue }
        $eq = $trimmed.IndexOf('=')
        if ($eq -lt 1) { continue }
        $key = $trimmed.Substring(0, $eq).Trim()
        $value = $trimmed.Substring($eq + 1).Trim()
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        if ($value -ne '') { $values[$key] = $value }
    }
    return $values
}

function Get-Setting([hashtable] $Values, [string] $Key, [string] $Default) {
    if ($Values.ContainsKey($Key)) { return $Values[$Key] }
    return $Default
}

<#
    The stack's settings with defaults applied and checked. Secrets stay in the returned object (never printed);
    callers pass them on through environment variables only.
#>
function Get-StackConfig([string] $Customer, [string] $FrontendMode) {
    $values = Read-StackEnv
    $config = [ordered]@{
        StackName          = Get-Setting $values 'STACK_NAME' 'beacon-devstack'
        ActiveCustomer     = Get-Setting $values 'ACTIVE_CUSTOMER' $script:FullHistoryCustomer
        SqlMode            = Get-Setting $values 'SQL_MODE' 'container'
        SqlUser            = Get-Setting $values 'SQL_USER' 'sa'
        SqlPassword        = Get-Setting $values 'SQL_PASSWORD' ''
        SqlHostPort        = [int](Get-Setting $values 'SQL_HOST_PORT' '14333')
        SqlInstancePort    = [int](Get-Setting $values 'SQL_HOST_INSTANCE_PORT' '1433')
        JwtSecret          = Get-Setting $values 'JWT_SECRET' ''
        FrontendMode       = Get-Setting $values 'FRONTEND_MODE' 'container'
        FrontendRepo       = Get-Setting $values 'FRONTEND_REPO' (Join-Path (Split-Path -Parent $script:RepoRoot) 'beacon-culinary-pos-front-end')
        HttpsPort          = [int](Get-Setting $values 'HTTPS_PORT' '443')
        HttpPort           = [int](Get-Setting $values 'HTTP_PORT' '80')
        FirstAdminName     = Get-Setting $values 'FIRST_ADMIN_NAME' 'Test Admin'
        FirstAdminEmail    = Get-Setting $values 'FIRST_ADMIN_EMAIL' 'admin@testcustomer.test'
        SmokeEmail         = Get-Setting $values 'SMOKE_EMAIL' ''
        SmokePassword      = Get-Setting $values 'SMOKE_PASSWORD' ''
        PlatformDatabase   = Get-Setting $values 'PLATFORM_DATABASE' 'pos_platform'
        PlatformPassword   = Get-Setting $values 'PLATFORM_DB_PASSWORD' ''
        # T1-3: multi (every customer at its own address) by default; single serves ACTIVE_CUSTOMER only.
        TenancyMode        = Get-Setting $values 'TENANCY_MODE' 'multi'
        # The raw settings, for the customers' SECRET_<NAME>_DB_* values (never printed).
        Values             = $values
    }
    if ($Customer) { $config.ActiveCustomer = $Customer }
    if ($FrontendMode) { $config.FrontendMode = $FrontendMode }

    Assert-CustomerName $config.ActiveCustomer
    if ($config.StackName -cnotmatch '^[a-z0-9][a-z0-9_-]{0,40}$') { throw 'STACK_NAME must be lower-case letters, digits, "-" and "_".' }
    if (@('container', 'host') -notcontains $config.SqlMode) { throw "SQL_MODE must be container or host, was '$($config.SqlMode)'." }
    if ($config.PlatformDatabase -cnotmatch '^[a-z][a-z0-9_]{2,63}$') { throw 'PLATFORM_DATABASE must be lower-case letters, digits and _ (3-64), starting with a letter.' }
    if (@('single', 'multi') -notcontains $config.TenancyMode) { throw "TENANCY_MODE must be single or multi, was '$($config.TenancyMode)'." }
    if (@('container', 'host', 'none') -notcontains $config.FrontendMode) { throw "FRONTEND_MODE must be container, host or none, was '$($config.FrontendMode)'." }
    if ($config.SqlMode -eq 'container' -and $config.SqlUser -ne 'sa') { throw 'SQL_USER must be sa (or blank) with SQL_MODE=container.' }
    if ([string]::IsNullOrEmpty($config.SqlPassword)) { throw 'SQL_PASSWORD is not set in devstack/stack.env.' }
    if ($config.SqlMode -eq 'container') {
        # SQL Server refuses a weaker sa password and the container never becomes healthy.
        $classes = @('[A-Z]', '[a-z]', '\d', '[^A-Za-z0-9]' | Where-Object { $config.SqlPassword -cmatch $_ }).Count
        if ($config.SqlPassword.Length -lt 8 -or $classes -lt 3) {
            throw 'SQL_PASSWORD must have at least 8 characters and 3 of: upper case, lower case, digits, symbols (SQL Server''s rule for sa).'
        }
    }
    if ($config.JwtSecret.Length -lt 32) { throw 'JWT_SECRET in devstack/stack.env must be at least 32 characters.' }
    if (-not [IO.Path]::IsPathRooted($config.FrontendRepo)) {
        $config.FrontendRepo = [IO.Path]::GetFullPath((Join-Path $script:DevstackRoot $config.FrontendRepo))
    }
    $config.PortSuffix = ''
    if ($config.HttpsPort -ne 443) { $config.PortSuffix = ":$($config.HttpsPort)" }
    $config.Network = "$($config.StackName)_stack"
    return [pscustomobject]$config
}

function Get-CustomerUrl($Config, [string] $Customer) {
    return "https://$Customer.pos.localhost$($Config.PortSuffix)"
}

<#
    T1-10: the frontend image's build id, as the frontend's next.config.mjs makes it, from FRONTEND_REPO's git (the
    image's build context has no .git, so without this the image reports "nogit"): <version>-<short HEAD>-<time>,
    "-dirty" after the hash when the working tree has changes. Clean: the commit's time, so the same commit gives the
    same id and Docker reuses its build. Dirty: now, since the files can change without a new commit. $null when
    FRONTEND_REPO isn't a git repository.
#>
function Get-FrontendBuildId($Config) {
    $ErrorActionPreference = 'Continue'   # git's standard error (not a repository) must not stop the script
    $sha = & git -C $Config.FrontendRepo rev-parse --short HEAD 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $sha) { return $null }
    $version = (Get-Content -LiteralPath (Join-Path $Config.FrontendRepo 'package.json') -Raw | ConvertFrom-Json).version
    $dirty = @(& git -C $Config.FrontendRepo status --porcelain 2>$null).Count -gt 0
    $ms = if ($dirty) { [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() } else { [long](& git -C $Config.FrontendRepo log -1 --format=%ct) * 1000 }
    $digits = '0123456789abcdefghijklmnopqrstuvwxyz'; $time = ''
    do { $time = $digits[[int]($ms % 36)] + $time; $ms = [long][math]::Floor($ms / 36) } while ($ms -gt 0)
    return "$version-$sha$(if ($dirty) { '-dirty' })-$time"
}

<#
    The environment compose reads on top of stack.env (this process's variables win over --env-file): the values
    worked out here, and the frontend upstream for the chosen frontend mode.
#>
function Set-ComposeEnvironment($Config, [string] $FrontendUpstream) {
    $profiles = @()
    if ($Config.SqlMode -eq 'container') { $profiles += 'sql' }
    if ($Config.FrontendMode -eq 'container') { $profiles += 'frontend' }
    $env:COMPOSE_PROFILES = $profiles -join ','
    # Local builds only: no build record attached, so an unchanged image keeps its ID and its container isn't recreated.
    $env:BUILDX_NO_DEFAULT_ATTESTATIONS = '1'
    $env:STACK_NAME = $Config.StackName
    $env:ACTIVE_CUSTOMER = $Config.ActiveCustomer
    $env:FRONTEND_REPO = $Config.FrontendRepo
    $env:PUBLIC_PORT_SUFFIX = $Config.PortSuffix
    $env:HTTPS_PORT = "$($Config.HttpsPort)"
    $env:HTTP_PORT = "$($Config.HttpPort)"
    $env:SQL_HOST_PORT = "$($Config.SqlHostPort)"
    if ($Config.SqlMode -eq 'container') {
        $env:SQL_SERVER = 'sql'
        $env:SQL_PORT = '1433'
    } else {
        $env:SQL_SERVER = 'host.docker.internal'
        $env:SQL_PORT = "$($Config.SqlInstancePort)"
    }
    if ($FrontendUpstream) { $env:FRONTEND_UPSTREAM = $FrontendUpstream }
    $env:PLATFORM_DATABASE = $Config.PlatformDatabase
    $env:APP_TENANCY_MODE = $Config.TenancyMode
    # T1-3: in multi mode Caddy serves every *.pos.localhost (its single-mode guard matches nothing). T1-7: invite links
    # are on each customer's primary host, from the platform database (no base URL is set).
    if ($Config.TenancyMode -eq 'multi') {
        $env:CADDY_ACTIVE_HOST = '*.pos.localhost'
        # Every customer's address may call the API (the browser's Origin, even on its own site).
        $env:STACK_CORS_ALLOWED_ORIGINS = "https://*.pos.localhost$($Config.PortSuffix)"
    } else {
        $env:CADDY_ACTIVE_HOST = "$($Config.ActiveCustomer).pos.localhost"
        $env:STACK_CORS_ALLOWED_ORIGINS = Get-CustomerUrl $Config $Config.ActiveCustomer
    }
}

<# T1-3: the customers the backend serves: every managed customer in multi mode, ACTIVE_CUSTOMER in single mode. #>
function Get-ServedCustomers($Config) {
    if ($Config.TenancyMode -eq 'multi') { return Get-ManagedCustomers }
    return @($Config.ActiveCustomer)
}

<# T1-3: the account smoke.ps1 signs in with at a customer: SMOKE_EMAIL at beaconculinary, smoke@<name>.test elsewhere. #>
function Get-SmokeEmail($Config, [string] $Name) {
    if ($Name -eq $script:FullHistoryCustomer) { return $Config.SmokeEmail }
    return "smoke@$Name.test"
}

<# One JSON request through the gateway; returns the status and parsed body. Nothing is printed. #>
function Invoke-StackApi([string] $Method, [string] $Url, $Body) {
    Add-Type -AssemblyName System.Net.Http
    $client = [System.Net.Http.HttpClient]::new()
    try {
        $client.Timeout = [TimeSpan]::FromSeconds(30)
        $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), $Url)
        if ($null -ne $Body) {
            $request.Content = [System.Net.Http.StringContent]::new(($Body | ConvertTo-Json -Compress), [Text.Encoding]::UTF8, 'application/json')
        }
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $json = $null
        if ($text -and $text.TrimStart().StartsWith('{')) { try { $json = $text | ConvertFrom-Json } catch { $json = $null } }
        return [pscustomobject]@{ Status = [int]$response.StatusCode; Json = $json }
    } finally {
        $client.Dispose()
    }
}

<#
    T1-3: the smoke account at a customer other than beaconculinary (which uses the demo SMOKE_EMAIL): an admin
    smoke@<name>.test whose password is SMOKE_PASSWORD. Made when its database is (up.ps1, new-customer.ps1, and again
    after reset.ps1): FirstAdminCommand's invite link is captured, never shown, and accepted through the gateway with
    SMOKE_PASSWORD. Nothing to do when the account already signs in. Only for https://<name>.pos.localhost addresses.
#>
function Initialize-SmokeAccount($Config, [string] $Name) {
    if ($Name -eq $script:FullHistoryCustomer) { return }
    if (-not $Config.SmokePassword) { Write-Problem "SMOKE_PASSWORD isn't set in devstack/stack.env: no smoke account at $Name."; return }
    $base = Get-CustomerUrl $Config $Name
    if ($base -cnotmatch '^https://[a-z][a-z0-9]{2,29}\.pos\.localhost(:\d{1,5})?$') { throw "Smoke accounts are made only at *.pos.localhost addresses, not $base." }
    $email = Get-SmokeEmail $Config $Name
    $signIn = Invoke-StackApi 'POST' "$base/api/auth/login" @{ email = $email; password = $Config.SmokePassword }
    if ($signIn.Status -eq 200) { Write-Ok "smoke account at ${Name}: $email (exists)"; return }

    if (-not (Test-Path -LiteralPath $script:Jar)) { throw "The backend jar is missing ($script:Jar)." }
    $saved = @{}
    foreach ($variable in 'SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME', 'SPRING_DATASOURCE_PASSWORD') {
        $saved[$variable] = [Environment]::GetEnvironmentVariable($variable)
    }
    $env:SPRING_DATASOURCE_URL = Get-HostJdbcUrl $Config $Name
    $env:SPRING_DATASOURCE_USERNAME = $Config.SqlUser
    $env:SPRING_DATASOURCE_PASSWORD = $Config.SqlPassword
    $token = $null
    try {
        $ErrorActionPreference = 'Continue'
        $javaArgs = @('-cp', $script:Jar, '-Dloader.main=com.beaconculinary.api.ops.FirstAdminCommand',
                      'org.springframework.boot.loader.launch.PropertiesLauncher', '--name', 'Smoke check', '--email', $email, '--base-url', $base)
        # Captured, never shown: it holds the invite link.
        $output = (& java @javaArgs 2>&1 | ForEach-Object { "$_" }) -join "`n"
        if ($LASTEXITCODE -eq 3) {
            # Another admin has set a password (a real first admin): this extra account needs --force.
            $output = (& java @($javaArgs + @('--force', '--reason', 'local smoke check account (devstack)')) 2>&1 | ForEach-Object { "$_" }) -join "`n"
        }
        if ($LASTEXITCODE -ne 0) { throw "FirstAdminCommand for the smoke account at $Name failed (exit code $LASTEXITCODE; its output isn't shown: it may hold a link)." }
        $match = [regex]::Match($output, '#t=([A-Za-z0-9_-]+)')
        if (-not $match.Success) { throw "FirstAdminCommand gave no invite link for the smoke account at $Name." }
        $token = $match.Groups[1].Value
        $output = $null
    } finally {
        foreach ($variable in $saved.Keys) { [Environment]::SetEnvironmentVariable($variable, $saved[$variable]) }
    }
    # An ADMIN's invite also asks for a PIN: a random one, never stored or shown (the smoke check signs in by password).
    # Another PIN if the PIN rules refuse it (the invite stays valid until accepted).
    $accepted = $null
    for ($try = 0; $try -lt 3; $try++) {
        $bytes = [byte[]]::new(4)
        [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
        $pin = '{0:D6}' -f ([BitConverter]::ToUInt32($bytes, 0) % 1000000)
        $accepted = Invoke-StackApi 'POST' "$base/api/auth/invites/accept" @{ token = $token; password = $Config.SmokePassword; pin = $pin }
        $pin = $null
        $pinRefused = $accepted.Status -eq 400 -and $accepted.Json -and $accepted.Json.PSObject.Properties.Name -contains 'details' -and
            $accepted.Json.details.fields -and $accepted.Json.details.fields.PSObject.Properties.Name -contains 'pin' -and
            -not ($accepted.Json.details.fields.PSObject.Properties.Name -contains 'password')
        $pinRefused = $pinRefused -or ($accepted.Status -eq 409 -and $accepted.Json -and $accepted.Json.code -eq 'PIN_IN_USE')
        if (-not $pinRefused) { break }
    }
    $token = $null
    if ($accepted.Status -ne 200) {
        $code = if ($accepted.Json -and $accepted.Json.PSObject.Properties.Name -contains 'code') { " $($accepted.Json.code)" } else { '' }
        throw "Accepting the smoke account's invite at $Name failed: HTTP $($accepted.Status)$code."
    }
    $signIn = Invoke-StackApi 'POST' "$base/api/auth/login" @{ email = $email; password = $Config.SmokePassword }
    if ($signIn.Status -ne 200) { throw "The new smoke account at $Name can't sign in: HTTP $($signIn.Status)." }
    Write-Ok "smoke account at ${Name}: $email (made)"
}

<#
    Runs docker and returns its output lines (standard output and error together, as text); the exit code is in
    $LASTEXITCODE. Every docker call whose output is captured goes through here: Windows PowerShell 5.1 turns a
    native program's redirected standard error into errors, which stop the scripts ($ErrorActionPreference is
    Stop), so a harmless notice such as "Unable to find image ... locally" before a pull would end the run.
#>
function Invoke-Docker([string[]] $Arguments) {
    $ErrorActionPreference = 'Continue'
    $output = & docker @Arguments 2>&1
    return ,@($output | ForEach-Object { "$_" })
}

<# The id of this stack's running container for a service, or nothing. #>
function Get-RunningContainerId($Config, [string] $Service) {
    $ids = Invoke-Docker @('ps', '-q', '--filter', "name=^$($Config.StackName)-$Service-1$")
    if ($LASTEXITCODE -ne 0) { return $null }
    return ($ids | Where-Object { $_ -match '^[0-9a-f]{12,}$' } | Select-Object -First 1)
}

function Invoke-Compose {
    $composeArgs = @('compose', '--project-directory', $script:DevstackRoot, '-f', $script:ComposeFile,
                     '--env-file', $script:StackEnvFile) + $args
    & docker @composeArgs
    if ($LASTEXITCODE -ne 0) { throw "docker compose $($args -join ' ') failed (exit code $LASTEXITCODE)." }
}

function Assert-Docker {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw 'docker is not on the PATH. Install Docker Desktop (docs/2026-11-23-local-stack.md, what to install).'
    }
    $null = Invoke-Docker @('info', '--format', '{{.ServerVersion}}')
    if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop is not running. Start it and wait until it says "Engine running".' }
}

function Assert-StackNetwork($Config) {
    $null = Invoke-Docker @('network', 'inspect', $Config.Network)
    if ($LASTEXITCODE -ne 0) { throw "The stack isn't running (no network $($Config.Network)). Run devstack/scripts/up.ps1 first." }
}

<# docker run arguments that reach SQL Server from a throwaway container on the stack network. #>
function Get-SqlTarget($Config) {
    if ($Config.SqlMode -eq 'container') {
        return [pscustomobject]@{ Server = 'sql'; Port = 1433; DockerArgs = @('--network', $Config.Network) }
    }
    return [pscustomobject]@{ Server = 'host.docker.internal'; Port = $Config.SqlInstancePort
                              DockerArgs = @('--add-host', 'host.docker.internal:host-gateway') }
}

<#
    Runs T-SQL with sqlcmd in a throwaway container. Returns the output lines (no headers, "|" between columns).
    -Database defaults to master. Throws when sqlcmd fails.
#>
function Invoke-StackSql($Config, [string] $Query, [string] $Database = 'master', [hashtable] $Variables) {
    $target = Get-SqlTarget $Config
    $env:SQLCMDPASSWORD = $Config.SqlPassword
    # T1-1: values a query needs (a login's password) reach sqlcmd as environment variables, which it reads as
    # scripting variables, $(NAME) in the query: never on a command line, removed afterwards.
    $passed = @('-e', 'SQLCMDPASSWORD')
    if ($Variables) {
        foreach ($key in $Variables.Keys) {
            [Environment]::SetEnvironmentVariable($key, [string]$Variables[$key])
            $passed += @('-e', $key)
        }
    }
    try {
        $dockerArgs = @('run', '--rm') + $passed + $target.DockerArgs + @(
            '--entrypoint', '/opt/mssql-tools18/bin/sqlcmd', $script:SqlImage,
            '-C', '-S', "$($target.Server),$($target.Port)", '-U', $Config.SqlUser, '-d', $Database,
            '-b', '-I', '-h', '-1', '-W', '-s', '|', '-Q', "SET NOCOUNT ON; $Query")
        $output = Invoke-Docker $dockerArgs
        if ($LASTEXITCODE -ne 0) {
            throw "sqlcmd failed on $Database (exit code $LASTEXITCODE): $(($output | Out-String).Trim())"
        }
        # The leading comma keeps a one-row result an array: PowerShell would otherwise unroll it into a string,
        # and [0] would be its first character.
        return ,@($output | ForEach-Object { "$_" } | Where-Object { $_ -ne '' })
    } finally {
        Remove-Item Env:SQLCMDPASSWORD -ErrorAction SilentlyContinue
        if ($Variables) { foreach ($key in $Variables.Keys) { [Environment]::SetEnvironmentVariable($key, $null) } }
    }
}

function Wait-SqlServer($Config, [int] $TimeoutSeconds = 120) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ($true) {
        try {
            $null = Invoke-StackSql $Config 'SELECT 1'
            return
        } catch {
            if ((Get-Date) -gt $deadline) { throw "SQL Server didn't answer within $TimeoutSeconds s: $($_.Exception.Message)" }
            Start-Sleep -Seconds 3
        }
    }
}

function Test-StackDatabase($Config, [string] $Name) {
    Assert-CustomerName $Name
    $rows = Invoke-StackSql $Config "SELECT COUNT(*) FROM sys.databases WHERE name = N'$Name'"
    return ($rows[0].Trim() -eq '1')
}

function New-EmptyStackDatabase($Config, [string] $Name) {
    Assert-CustomerName $Name
    $null = Invoke-StackSql $Config "CREATE DATABASE [$Name] COLLATE $($script:Collation)"
}

function Remove-StackDatabase($Config, [string] $Name) {
    Assert-CustomerName $Name
    $null = Invoke-StackSql $Config ("IF DB_ID(N'$Name') IS NOT NULL BEGIN " +
        "ALTER DATABASE [$Name] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE [$Name]; END")
}

<# The JDBC address of a database for Java on this machine (NewDatabaseCommand, FirstAdminCommand). #>
function Get-HostJdbcUrl($Config, [string] $Name) {
    $port = $Config.SqlHostPort
    if ($Config.SqlMode -eq 'host') { $port = $Config.SqlInstancePort }
    return "jdbc:sqlserver://localhost:$port;databaseName=$Name;encrypt=true;trustServerCertificate=true"
}

<#
    A clean customer database the T0-7 way: scripts/new-customer-database.ps1 (NewDatabaseCommand with the starter
    template, then FirstAdminCommand). The database must already exist, empty, with the collation.
    The first admin's invite link is printed on this console only.
#>
function Initialize-CleanCustomerDatabase($Config, [string] $Name) {
    Assert-CustomerName $Name
    if (-not (Test-Path -LiteralPath $script:Jar)) { throw "The backend jar is missing ($script:Jar). Run up.ps1 (it builds it) or mvnw package." }
    $saved = @{}
    foreach ($variable in 'SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME', 'SPRING_DATASOURCE_PASSWORD') {
        $saved[$variable] = [Environment]::GetEnvironmentVariable($variable)
    }
    $env:SPRING_DATASOURCE_URL = Get-HostJdbcUrl $Config $Name
    $env:SPRING_DATASOURCE_USERNAME = $Config.SqlUser
    $env:SPRING_DATASOURCE_PASSWORD = $Config.SqlPassword
    try {
        $email = $Config.FirstAdminEmail
        if ($Name -ne $script:CleanCustomer) { $email = "admin@$Name.test" }
        & (Join-Path $script:RepoRoot 'scripts\new-customer-database.ps1') -Jar $script:Jar -Name $Config.FirstAdminName `
            -Email $email -BaseUrl (Get-CustomerUrl $Config $Name) -Starter
        if ($LASTEXITCODE -ne 0) { throw "scripts/new-customer-database.ps1 failed for $Name (exit code $LASTEXITCODE)." }
    } finally {
        foreach ($variable in $saved.Keys) {
            [Environment]::SetEnvironmentVariable($variable, $saved[$variable])
        }
    }
}

function Get-ManagedCustomers {
    $names = [System.Collections.Generic.List[string]]::new()
    $names.Add($script:FullHistoryCustomer)
    $names.Add($script:CleanCustomer)
    $file = Join-Path $script:StateDir 'customers.txt'
    if (Test-Path -LiteralPath $file) {
        foreach ($line in Get-Content -LiteralPath $file) {
            $name = $line.Trim()
            if ($name -cmatch $script:CustomerNamePattern -and -not $names.Contains($name)) { $names.Add($name) }
        }
    }
    return $names.ToArray()
}

function Add-ManagedCustomer([string] $Name) {
    Assert-CustomerName $Name
    if (-not (Test-Path -LiteralPath $script:StateDir)) { $null = New-Item -ItemType Directory -Path $script:StateDir }
    $file = Join-Path $script:StateDir 'customers.txt'
    if ((Get-ManagedCustomers) -notcontains $Name) { Add-Content -LiteralPath $file -Value $Name -Encoding ascii }
}

<#
    Creates a managed customer's database the way it starts. beaconculinary: empty here; once it is registered (full
    history), the customer migration command gives it every migration from V1 with the demo data (T1-4). The rest: the
    T0-7 way.
#>
function Initialize-CustomerDatabase($Config, [string] $Name) {
    Write-Step "Creating database $Name"
    New-EmptyStackDatabase $Config $Name
    if ($Name -eq $script:FullHistoryCustomer) {
        Write-Ok "$Name : created empty; the customer migration command fills it"
        return
    }
    try {
        Initialize-CleanCustomerDatabase $Config $Name
    } catch {
        # Never leave a half-made database: the next run would find it "existing" and skip setting it up.
        Write-Problem "Setting up $Name failed; dropping it so the next run starts it again."
        try { Remove-StackDatabase $Config $Name } catch { Write-Problem "Could not drop ${Name}: $($_.Exception.Message)" }
        throw
    }
}

<# The backend's /health from inside the stack (no certificate or hosts file needed). True when UP. #>
function Test-BackendHealthy($Config) {
    $output = Invoke-Docker @('exec', "$($Config.StackName)-caddy-1", 'wget', '-q', '-O', '-', 'http://backend:8080/health')
    return ($LASTEXITCODE -eq 0 -and "$output" -match '"status"\s*:\s*"UP"')
}

function Wait-BackendHealthy($Config, [int] $TimeoutSeconds = 240) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while (-not (Test-BackendHealthy $Config)) {
        if ((Get-Date) -gt $deadline) {
            throw "The backend wasn't healthy within $TimeoutSeconds s. See: docker compose -p $($Config.StackName) logs backend"
        }
        Start-Sleep -Seconds 3
    }
}

<# Starting-state figures of a database: collation, Flyway version and row counts per table. #>
function Get-DatabaseState($Config, [string] $Name) {
    $collation = (Invoke-StackSql $Config "SELECT CONVERT(nvarchar(128), DATABASEPROPERTYEX(N'$Name', 'Collation'))")[0].Trim()
    $version = (Invoke-StackSql $Config ("SELECT TOP 1 version FROM flyway_schema_history WHERE success = 1 " +
        "AND version IS NOT NULL ORDER BY installed_rank DESC") $Name)[0].Trim()
    $counts = [ordered]@{}
    $rows = Invoke-StackSql $Config ("SELECT t.name, SUM(p.rows) FROM sys.tables t JOIN sys.partitions p " +
        "ON p.object_id = t.object_id AND p.index_id IN (0, 1) WHERE t.is_ms_shipped = 0 " +
        # provisioning_runs (the platform database) is a log that grows on every up.ps1, not a starting state.
        "AND t.name NOT IN ('flyway_schema_history', 'provisioning_runs') GROUP BY t.name ORDER BY t.name") $Name
    foreach ($row in $rows) {
        $parts = $row.Split('|')
        if ($parts.Count -eq 2) { $counts[$parts[0].Trim()] = [long]$parts[1].Trim() }
    }
    return [pscustomobject]@{ Name = $Name; Collation = $collation; Version = $version; RowCounts = $counts }
}

# --- T1-1: the platform database and the customers' database logins (docs/2026-11-24-platform-database.md) ---

<#
    A database login's password: required, SQL Server's rule (8+ characters, 3 of upper case, lower case, digits,
    symbols), and no single quote (it reaches T-SQL as a sqlcmd variable inside N'...'). Errors name the setting only.
#>
function Assert-LoginPassword([string] $Setting, [string] $Value) {
    if ([string]::IsNullOrEmpty($Value)) { throw "$Setting is not set in devstack/stack.env." }
    $classes = @('[A-Z]', '[a-z]', '\d', '[^A-Za-z0-9]' | Where-Object { $Value -cmatch $_ }).Count
    if ($Value.Length -lt 8 -or $classes -lt 3) {
        throw "$Setting must have at least 8 characters and 3 of: upper case, lower case, digits, symbols."
    }
    if ($Value.Contains("'")) { throw "$Setting must not contain a single quote (')." }
}

function Assert-PlatformSettings($Config) {
    Assert-LoginPassword 'PLATFORM_DB_PASSWORD' $Config.PlatformPassword
}

<# T1-10: the stack turns offline selling on, and the backend needs its grant key (Base64, 32+ bytes). Errors name the setting, never the value. #>
function Assert-OfflineGrantKey($Config) {
    $key = Get-Setting $Config.Values 'OFFLINE_GRANT_SIGNING_KEY' ''
    if (-not $key) { throw 'OFFLINE_GRANT_SIGNING_KEY is not set in devstack/stack.env (offline selling''s grant key: Base64 of 32+ random bytes; stack.env.example says how to make one).' }
    $bytes = $null
    try { $bytes = [Convert]::FromBase64String($key) } catch { throw 'OFFLINE_GRANT_SIGNING_KEY in devstack/stack.env is not valid Base64.' }
    if ($bytes.Length -lt 32) { throw 'OFFLINE_GRANT_SIGNING_KEY in devstack/stack.env must decode to at least 32 bytes.' }
    if ($key -ceq $Config.JwtSecret) { throw 'OFFLINE_GRANT_SIGNING_KEY must be a value of its own, not JWT_SECRET''s.' }
}

<#
    A customer's two database logins (T1-4), errors naming the setting, never a value:
      the app login (the backend): SECRET_<NAME>_DB_LOGIN and _DB_PASSWORD, the secrets <name>-db-login and -db-password;
      the migration login (the migration command only): SECRET_<NAME>_DB_MIGRATION_LOGIN and _DB_MIGRATION_PASSWORD,
      the secrets <name>-db-migration-login and -db-migration-password.
#>
function Get-CustomerSecretValues($Config, [string] $Name) {
    Assert-CustomerName $Name
    $upper = $Name.ToUpperInvariant()
    $loginSetting = "SECRET_${upper}_DB_LOGIN"
    $passwordSetting = "SECRET_${upper}_DB_PASSWORD"
    $login = Get-Setting $Config.Values $loginSetting ''
    if (-not $login) { throw "$loginSetting is not set in devstack/stack.env (the login of customer $Name's database, e.g. pos_$Name)." }
    if ($login -eq 'sa' -or $login -eq $script:PlatformLogin) { throw "$loginSetting must be a login of its own, not $login." }
    if ($login -cnotmatch '^[a-z][a-z0-9_]{2,63}$') { throw "$loginSetting must be lower-case letters, digits and _ (3-64), starting with a letter." }
    $password = Get-Setting $Config.Values $passwordSetting ''
    Assert-LoginPassword $passwordSetting $password
    $migrationLoginSetting = "SECRET_${upper}_DB_MIGRATION_LOGIN"
    $migrationPasswordSetting = "SECRET_${upper}_DB_MIGRATION_PASSWORD"
    $migrationLogin = Get-Setting $Config.Values $migrationLoginSetting ''
    if (-not $migrationLogin) { throw "$migrationLoginSetting is not set in devstack/stack.env (the migration login of customer $Name's database, e.g. pos_${Name}_migrate)." }
    if ($migrationLogin -eq 'sa' -or $migrationLogin -eq $script:PlatformLogin -or $migrationLogin -eq $login) {
        throw "$migrationLoginSetting must be a login of its own, not $migrationLogin."
    }
    if ($migrationLogin -cnotmatch '^[a-z][a-z0-9_]{2,63}$') { throw "$migrationLoginSetting must be lower-case letters, digits and _ (3-64), starting with a letter." }
    $migrationPassword = Get-Setting $Config.Values $migrationPasswordSetting ''
    Assert-LoginPassword $migrationPasswordSetting $migrationPassword
    return [pscustomobject]@{ Login = $login; Password = $password; LoginSecret = "$Name-db-login"; PasswordSecret = "$Name-db-password"
                              MigrationLogin = $migrationLogin; MigrationPassword = $migrationPassword
                              MigrationLoginSecret = "$Name-db-migration-login"; MigrationPasswordSecret = "$Name-db-migration-password" }
}

<#
    Makes sure the login exists with this password (created, or its password set again) and has its rights in this
    database only. owner (the platform login, a customer's migration login): db_owner. app (T1-4, a customer's app
    login, what the backend uses): read, write and execute only (db_datareader, db_datawriter, GRANT EXECUTE), and out of
    db_owner if an earlier stack made it one. The password reaches sqlcmd as an environment variable.
#>
function Set-DatabaseLogin($Config, [string] $Login, [string] $Password, [string] $Database,
                           [ValidateSet('owner', 'app')] [string] $Rights = 'owner') {
    if ($Login -cnotmatch '^[a-z][a-z0-9_]{2,63}$') { throw "Not a login name: $Login" }
    $null = Invoke-StackSql $Config ("IF SUSER_ID(N'$Login') IS NULL CREATE LOGIN [$Login] WITH PASSWORD = N'" + '$(DEVSTACK_LOGIN_PASSWORD)' +
        "', CHECK_POLICY = ON, DEFAULT_DATABASE = [$Database]; ELSE ALTER LOGIN [$Login] WITH PASSWORD = N'" + '$(DEVSTACK_LOGIN_PASSWORD)' + "';") `
        'master' @{ DEVSTACK_LOGIN_PASSWORD = $Password }
    $user = "IF USER_ID(N'$Login') IS NULL CREATE USER [$Login] FOR LOGIN [$Login]; "
    if ($Rights -eq 'owner') {
        $user += "IF IS_ROLEMEMBER('db_owner', N'$Login') = 0 ALTER ROLE db_owner ADD MEMBER [$Login];"
    } else {
        $user += "IF IS_ROLEMEMBER('db_owner', N'$Login') = 1 ALTER ROLE db_owner DROP MEMBER [$Login]; " +
            "IF IS_ROLEMEMBER('db_datareader', N'$Login') = 0 ALTER ROLE db_datareader ADD MEMBER [$Login]; " +
            "IF IS_ROLEMEMBER('db_datawriter', N'$Login') = 0 ALTER ROLE db_datawriter ADD MEMBER [$Login]; " +
            "GRANT EXECUTE TO [$Login];"
    }
    $null = Invoke-StackSql $Config $user $Database
}

function Test-PlatformDatabase($Config) {
    $rows = Invoke-StackSql $Config "SELECT COUNT(*) FROM sys.databases WHERE name = N'$($Config.PlatformDatabase)'"
    return ($rows[0].Trim() -eq '1')
}

function Remove-PlatformDatabase($Config) {
    $name = $Config.PlatformDatabase
    $null = Invoke-StackSql $Config ("IF DB_ID(N'$name') IS NOT NULL BEGIN " +
        "ALTER DATABASE [$name] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE [$name]; END")
}

<# Runs TenantRegisterCommand on this machine against the platform database, as its own login. Returns the exit code. #>
function Invoke-TenantCommand($Config, [string[]] $Arguments) {
    if (-not (Test-Path -LiteralPath $script:Jar)) { throw "The backend jar is missing ($script:Jar). Run up.ps1 (it builds it)." }
    $saved = @{}
    foreach ($variable in 'APP_TENANCY_PLATFORM_URL', 'APP_TENANCY_PLATFORM_USERNAME', 'APP_TENANCY_PLATFORM_PASSWORD') {
        $saved[$variable] = [Environment]::GetEnvironmentVariable($variable)
    }
    $env:APP_TENANCY_PLATFORM_URL = Get-HostJdbcUrl $Config $Config.PlatformDatabase
    $env:APP_TENANCY_PLATFORM_USERNAME = $script:PlatformLogin
    $env:APP_TENANCY_PLATFORM_PASSWORD = $Config.PlatformPassword
    try {
        $ErrorActionPreference = 'Continue'
        $javaArgs = @('-cp', $script:Jar, '-Dloader.main=com.beaconculinary.api.ops.TenantRegisterCommand',
                      'org.springframework.boot.loader.launch.PropertiesLauncher') + $Arguments
        & java @javaArgs 2>&1 | ForEach-Object { Write-Host "       $_" }
        return $LASTEXITCODE
    } finally {
        foreach ($variable in $saved.Keys) { [Environment]::SetEnvironmentVariable($variable, $saved[$variable]) }
    }
}

<# The platform database: created with the collation if missing, its own login, and migrated. #>
function Initialize-PlatformDatabase($Config) {
    Assert-PlatformSettings $Config
    $name = $Config.PlatformDatabase
    if (-not (Test-PlatformDatabase $Config)) {
        $null = Invoke-StackSql $Config "CREATE DATABASE [$name] COLLATE $($script:Collation)"
        Write-Ok "$name created"
    }
    Set-DatabaseLogin $Config $script:PlatformLogin $Config.PlatformPassword $name
    $code = Invoke-TenantCommand $Config @('migrate')
    if ($code -ne 0) { throw "Migrating the platform database failed (exit code $code)." }
}

<# A stack customer's name in the platform database (tenants.display_name); T1-7: smoke.ps1 expects it from /api/public/branding. #>
function Get-StackDisplayName([string] $Name) {
    switch ($Name) { 'beaconculinary' { return 'Beacon Culinary' } 'testcustomer' { return 'Test Customer' } default { return $Name } }
}

<#
    A customer: its two database logins (T1-4: the app login, read, write and execute; the migration login, db_owner),
    and its registration in the platform database with <name>.pos.localhost and the four secret names. Idempotent
    (TenantRegisterCommand reports Unchanged).
#>
function Register-StackCustomer($Config, [string] $Name) {
    $secrets = Get-CustomerSecretValues $Config $Name
    Set-DatabaseLogin $Config $secrets.Login $secrets.Password $Name 'app'
    Set-DatabaseLogin $Config $secrets.MigrationLogin $secrets.MigrationPassword $Name 'owner'
    $displayName = Get-StackDisplayName $Name
    $server = 'sql'; $port = 1433
    if ($Config.SqlMode -eq 'host') { $server = 'host.docker.internal'; $port = $Config.SqlInstancePort }
    $fromBaseline = if ($Name -eq $script:FullHistoryCustomer) { 'false' } else { 'true' }
    $code = Invoke-TenantCommand $Config @('register', '--slug', $Name, '--name', $displayName, '--host', "$Name.pos.localhost",
        '--db-server', $server, '--db-port', "$port", '--db-name', $Name, '--db-options', 'encrypt=true;trustServerCertificate=true',
        '--login-secret', $secrets.LoginSecret, '--password-secret', $secrets.PasswordSecret, '--built-from-baseline', $fromBaseline,
        '--migration-login-secret', $secrets.MigrationLoginSecret, '--migration-password-secret', $secrets.MigrationPasswordSecret)
    if ($code -ne 0) { throw "Registering $Name in the platform database failed (exit code $code)." }
}

<#
    T1-4: MigrateCustomersCommand (docs/2026-11-26-customer-migrations.md) in a throwaway Java container on the stack
    network, as a pipeline job runs it: the database addresses the platform database holds are the stack's own (sql,
    as the backend sees them). The platform password and every customer's migration login reach it as environment
    variables named on the command line (-e NAME), never as values, and are removed from this process afterwards.
    Prints its table; returns its exit code (0 all updated or unchanged, 4 a customer failed).
#>
function Invoke-MigrateCustomers($Config, [string[]] $Arguments = @()) {
    if (-not (Test-Path -LiteralPath $script:Jar)) { throw "The backend jar is missing ($script:Jar). Run up.ps1 (it builds it)." }
    $target = Get-SqlTarget $Config
    $values = [ordered]@{
        APP_TENANCY_PLATFORM_URL      = "jdbc:sqlserver://$($target.Server):$($target.Port);databaseName=$($Config.PlatformDatabase);encrypt=true;trustServerCertificate=true"
        APP_TENANCY_PLATFORM_USERNAME = $script:PlatformLogin
        APP_TENANCY_PLATFORM_PASSWORD = $Config.PlatformPassword
    }
    foreach ($name in Get-ManagedCustomers) {
        $secrets = Get-CustomerSecretValues $Config $name
        $upper = $name.ToUpperInvariant()
        $values["SECRET_${upper}_DB_MIGRATION_LOGIN"] = $secrets.MigrationLogin
        $values["SECRET_${upper}_DB_MIGRATION_PASSWORD"] = $secrets.MigrationPassword
    }
    $passed = @()
    foreach ($key in $values.Keys) {
        [Environment]::SetEnvironmentVariable($key, [string]$values[$key])
        $passed += @('-e', $key)
    }
    try {
        $dockerArgs = @('run', '--rm') + $passed + $target.DockerArgs + @('-v', "$($script:Jar):/app.jar:ro", $script:JavaImage,
            'java', '-Duser.name=devstack', '-cp', '/app.jar', '-Dloader.main=com.beaconculinary.api.ops.MigrateCustomersCommand',
            'org.springframework.boot.loader.launch.PropertiesLauncher') + $Arguments
        $output = Invoke-Docker $dockerArgs
        $code = $LASTEXITCODE
        foreach ($line in $output) { Write-Host "       $line" }
        return $code
    } finally {
        foreach ($key in $values.Keys) { [Environment]::SetEnvironmentVariable($key, $null) }
    }
}

<#
    The registered customers: slug, status, hosts (* primary), database, built from the baseline; T1-4: the schema
    version the migration command last left it at, and its last update (outcome and UTC time). No secrets.
#>
function Get-PlatformCustomers($Config) {
    if (-not (Test-PlatformDatabase $Config)) { return @() }
    $rows = Invoke-StackSql $Config ("SELECT t.slug, t.status, ISNULL((SELECT STRING_AGG(CONCAT(h.host, CASE WHEN h.is_primary = 1 " +
        "THEN '*' ELSE '' END), ', ') WITHIN GROUP (ORDER BY h.is_primary DESC, h.host) FROM tenant_hosts h WHERE h.tenant_id = t.id), '-'), " +
        "ISNULL(d.database_name, '-'), ISNULL(CAST(CAST(d.built_from_baseline AS int) AS varchar(1)), '-'), " +
        "ISNULL(d.last_migrated_version, '-'), ISNULL((SELECT TOP (1) CONCAT(r.outcome, ' ', CONVERT(varchar(16), r.started_at, 120), ' UTC') " +
        "FROM provisioning_runs r WHERE r.tenant_id = t.id AND r.action = 'MIGRATE' ORDER BY r.id DESC), 'never') " +
        "FROM tenants t LEFT JOIN tenant_databases d ON d.tenant_id = t.id ORDER BY t.slug") $Config.PlatformDatabase
    return @($rows | ForEach-Object {
        $p = $_.Split('|')
        [pscustomobject]@{ Slug = $p[0].Trim(); Status = $p[1].Trim(); Hosts = $p[2].Trim(); Database = $p[3].Trim(); FromBaseline = $p[4].Trim() -eq '1'
                           SchemaVersion = $p[5].Trim(); LastUpdate = $p[6].Trim() }
    })
}

function Test-Port([int] $Port) {
    $listener = $null
    try {
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
        $listener.Start()
        return $true
    } catch {
        return $false
    } finally {
        if ($listener) { $listener.Stop() }
    }
}
