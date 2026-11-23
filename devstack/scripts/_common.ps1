<#
    T1-L0: shared by the devstack scripts (dot-sourced). Reads devstack/stack.env without printing it, works out the
    values compose needs, and runs sqlcmd and Flyway in throwaway containers, so the host needs only Docker, Java 17
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

# Pinned images (also in compose.yaml for sql). Flyway matches the application's Flyway (pom.xml).
$script:SqlImage = 'mcr.microsoft.com/mssql/server:2022-CU16-ubuntu-22.04'
$script:FlywayImage = 'flyway/flyway:10.20.1'
$script:Collation = 'SQL_Latin1_General_CP1_CI_AS'

# The two databases every stack has; new-customer.ps1 adds more (recorded in .state/customers.txt).
$script:FullHistoryCustomer = 'beaconculinary'
$script:CleanCustomer = 'testcustomer'
$script:CustomerNamePattern = '^[a-z][a-z0-9]{2,29}$'
$script:ReservedNames = @('master', 'model', 'msdb', 'tempdb', 'sql', 'backend', 'frontend', 'caddy', 'api', 'www')

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
    }
    if ($Customer) { $config.ActiveCustomer = $Customer }
    if ($FrontendMode) { $config.FrontendMode = $FrontendMode }

    Assert-CustomerName $config.ActiveCustomer
    if ($config.StackName -cnotmatch '^[a-z0-9][a-z0-9_-]{0,40}$') { throw 'STACK_NAME must be lower-case letters, digits, "-" and "_".' }
    if (@('container', 'host') -notcontains $config.SqlMode) { throw "SQL_MODE must be container or host, was '$($config.SqlMode)'." }
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
    The environment compose reads on top of stack.env (this process's variables win over --env-file): the values
    worked out here, and the frontend upstream for the chosen frontend mode.
#>
function Set-ComposeEnvironment($Config, [string] $FrontendUpstream) {
    $profiles = @()
    if ($Config.SqlMode -eq 'container') { $profiles += 'sql' }
    if ($Config.FrontendMode -eq 'container') { $profiles += 'frontend' }
    $env:COMPOSE_PROFILES = $profiles -join ','
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
function Invoke-StackSql($Config, [string] $Query, [string] $Database = 'master') {
    $target = Get-SqlTarget $Config
    $env:SQLCMDPASSWORD = $Config.SqlPassword
    try {
        $dockerArgs = @('run', '--rm', '-e', 'SQLCMDPASSWORD') + $target.DockerArgs + @(
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

<# beaconculinary: every migration from db/migration (none from db/baseline), as today's dev database. #>
function Initialize-FullHistoryDatabase($Config, [string] $Name) {
    Assert-CustomerName $Name
    $target = Get-SqlTarget $Config
    $migrations = Join-Path $script:RepoRoot 'src\main\resources\db\migration'
    $url = "jdbc:sqlserver://$($target.Server):$($target.Port);databaseName=$Name;encrypt=true;trustServerCertificate=true"
    $env:FLYWAY_PASSWORD = $Config.SqlPassword
    try {
        $dockerArgs = @('run', '--rm', '-e', 'FLYWAY_PASSWORD') + $target.DockerArgs + @(
            '-v', "${migrations}:/flyway/sql:ro", $script:FlywayImage,
            "-url=$url", "-user=$($Config.SqlUser)", '-locations=filesystem:/flyway/sql', '-baselineOnMigrate=false',
            '-outputType=json', 'migrate')
        $output = Invoke-Docker $dockerArgs
        $code = $LASTEXITCODE
        # Flyway's JSON names the database and the migrations, never the password.
        $text = ($output | ForEach-Object { "$_" }) -join "`n"
        if ($code -ne 0) { throw "Flyway failed on $Name (exit code $code): $text" }
        $start = $text.IndexOf('{')
        if ($start -ge 0) {
            $result = $text.Substring($start) | ConvertFrom-Json
            Write-Ok "$Name : $($result.migrationsExecuted) migrations applied, now at version $($result.targetSchemaVersion)"
        }
    } finally {
        Remove-Item Env:FLYWAY_PASSWORD -ErrorAction SilentlyContinue
    }
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

<# Creates a managed customer's database the way it starts: full history for beaconculinary, T0-7 for the rest. #>
function Initialize-CustomerDatabase($Config, [string] $Name) {
    Write-Step "Creating database $Name"
    New-EmptyStackDatabase $Config $Name
    try {
        if ($Name -eq $script:FullHistoryCustomer) {
            Initialize-FullHistoryDatabase $Config $Name
        } else {
            Initialize-CleanCustomerDatabase $Config $Name
        }
    } catch {
        # Never leave a half-made database: the next run would find it "existing" and skip it, and the backend
        # would then build an empty one from the baseline instead of the way this customer starts.
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
        "AND t.name <> 'flyway_schema_history' GROUP BY t.name ORDER BY t.name") $Name
    foreach ($row in $rows) {
        $parts = $row.Split('|')
        if ($parts.Count -eq 2) { $counts[$parts[0].Trim()] = [long]$parts[1].Trim() }
    }
    return [pscustomobject]@{ Name = $Name; Collation = $collation; Version = $version; RowCounts = $counts }
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
