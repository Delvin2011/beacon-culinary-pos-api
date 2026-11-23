<#
.SYNOPSIS
    T1-L0: what is running and whether it works: containers, the customer databases, health through the proxy,
    the hosts file and Caddy's certificate.

.PARAMETER Snapshot
    Saves each database's starting state (collation, Flyway version, row count per table) to devstack/.state, to
    compare with after a reset (-Compare). Take it right after the first up.ps1.

.PARAMETER Compare
    Compares each database with the saved snapshot; exits 1 if any differs.
#>
[CmdletBinding()]
param(
    [switch] $Snapshot,
    [switch] $Compare
)
. (Join-Path $PSScriptRoot '_common.ps1')

$config = Get-StackConfig
Assert-Docker
Set-ComposeEnvironment $config $null
$problems = 0

Write-Step "Containers ($($config.StackName))"
& docker ps -a --filter "label=com.docker.compose.project=$($config.StackName)" --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
Write-Host "   Active customer: $($config.ActiveCustomer)   SQL: $($config.SqlMode)   Frontend: $($config.FrontendMode)"

Write-Step 'Customer databases'
$sqlUp = $true
try { $null = Invoke-StackSql $config 'SELECT 1' } catch { $sqlUp = $false; Write-Problem 'SQL Server is not answering.'; $problems++ }
$states = @()
if ($sqlUp) {
    foreach ($name in Get-ManagedCustomers) {
        if (-not (Test-StackDatabase $config $name)) { Write-Problem "$name : missing (up.ps1 creates it)"; $problems++; continue }
        $state = Get-DatabaseState $config $name
        $states += $state
        $rows = ($state.RowCounts.Values | Measure-Object -Sum).Sum
        $line = "{0,-16} collation {1}, Flyway {2}, {3} tables, {4} rows" -f $name, $state.Collation, $state.Version, $state.RowCounts.Count, $rows
        if ($state.Collation -ne $script:Collation) { Write-Problem "$line  (expected $($script:Collation))"; $problems++ } else { Write-Ok $line }
    }
}

if ($Snapshot -and $states.Count -gt 0) {
    if (-not (Test-Path -LiteralPath $script:StateDir)) { $null = New-Item -ItemType Directory -Path $script:StateDir }
    foreach ($state in $states) {
        $state | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $script:StateDir "snapshot-$($state.Name).json") -Encoding utf8
    }
    Write-Ok "Snapshot saved in devstack/.state for $(($states | ForEach-Object Name) -join ', ')"
}

if ($Compare) {
    Write-Step 'Compared with the snapshot'
    foreach ($state in $states) {
        $file = Join-Path $script:StateDir "snapshot-$($state.Name).json"
        if (-not (Test-Path -LiteralPath $file)) { Write-Problem "$($state.Name): no snapshot (status.ps1 -Snapshot)"; $problems++; continue }
        $saved = Get-Content -LiteralPath $file -Raw | ConvertFrom-Json
        $differences = @()
        if ($saved.Collation -ne $state.Collation) { $differences += "collation $($saved.Collation) -> $($state.Collation)" }
        if ($saved.Version -ne $state.Version) { $differences += "Flyway $($saved.Version) -> $($state.Version)" }
        $savedCounts = @{}
        foreach ($property in $saved.RowCounts.PSObject.Properties) { $savedCounts[$property.Name] = [long]$property.Value }
        foreach ($table in (@($savedCounts.Keys) + @($state.RowCounts.Keys) | Sort-Object -Unique)) {
            $before = $null; $after = $null
            if ($savedCounts.ContainsKey($table)) { $before = $savedCounts[$table] }
            if ($state.RowCounts.Contains($table)) { $after = $state.RowCounts[$table] }
            if ($before -ne $after) { $differences += "$table $before -> $after" }
        }
        if ($differences.Count -eq 0) { Write-Ok "$($state.Name): same as the snapshot" }
        else { Write-Problem "$($state.Name): $($differences -join '; ')"; $problems++ }
    }
}

Write-Step 'Health'
$caddyRunning = Get-RunningContainerId $config 'caddy'
if (-not $caddyRunning) {
    Write-Problem 'The gateway is not running (up.ps1).'; $problems++
} elseif (Test-BackendHealthy $config) {
    Write-Ok 'backend /health UP (inside the stack)'
} else {
    Write-Problem "backend not healthy: docker compose -p $($config.StackName) logs backend"; $problems++
}

Write-Step 'This machine'
foreach ($name in Get-ManagedCustomers) {
    $hostName = "$name.pos.localhost"
    try {
        $addresses = [System.Net.Dns]::GetHostAddresses($hostName) | ForEach-Object { $_.ToString() }
        if ($addresses -contains '127.0.0.1') { Write-Ok "$hostName -> 127.0.0.1" }
        else { Write-Problem "$hostName -> $($addresses -join ', ') (expected 127.0.0.1)"; $problems++ }
    } catch {
        Write-Problem "$hostName doesn't resolve: add it to the hosts file (the doc, first-time set-up)."; $problems++
    }
}
$roots = @(Get-ChildItem Cert:\CurrentUser\Root, Cert:\LocalMachine\Root -ErrorAction SilentlyContinue |
    Where-Object { $_.Subject -like '*Caddy Local Authority*' })
if ($roots.Count -eq 0) {
    Write-Problem 'Caddy''s root certificate is not trusted on Windows (the doc, first-time set-up).'; $problems++
} else {
    Write-Ok "Caddy root trusted ($($roots.Count) in the Windows store; remove old ones after down.ps1 -Volumes)"
}
if ($caddyRunning) {
    $url = Get-CustomerUrl $config $config.ActiveCustomer
    try {
        $response = Invoke-WebRequest -Uri "$url/api/health" -UseBasicParsing -TimeoutSec 15
        Write-Ok "$url/api/health -> $($response.StatusCode)"
    } catch {
        Write-Problem "$url/api/health from this machine: $($_.Exception.Message)"; $problems++
    }
}

Write-Host ''
if ($problems -eq 0) { Write-Host 'All good.' -ForegroundColor Green; exit 0 }
Write-Host "$problems problem(s) above." -ForegroundColor Yellow
exit 1
