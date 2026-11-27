<#
.SYNOPSIS
    T1-1: checks everything before a slice is merged (until the GitHub pipelines exist): the backend's tests, the
    frontend's lint, types, unit tests, build and e2e tests, and the local stack's smoke check.

.DESCRIPTION
    Every step runs, whatever happened before it, and each is reported:
      0. cross-customer tests  mvnw test -Dgroups=cross-customer (T1-10: the tests proving no customer sees, changes
                            or receives another's; first, for fast feedback, and again in the full suite)
      1. backend tests      mvnw test (the full suite)
      2. frontend lint      npm run lint                 (in FRONTEND_REPO from devstack/stack.env)
      3. frontend types     npx tsc --noEmit
      4. frontend unit      npm test (Vitest)
      5. frontend build     npm run build
      6. frontend check:build  npm run check:build (no backend address or rewrite in the build; runs with -Quick
                            and -SkipE2E too)
      7. frontend e2e       npm run test:e2e (Playwright; builds the app again for its mock API)
      8. stack up           up.ps1 (always: it is idempotent and rebuilds the backend, so the smoke check tests the
                            code being merged)
      9. stack status       status.ps1
     10. smoke              smoke.ps1
     11. stack frontend build  the build id in the stack's /sw.js names FRONTEND_REPO's HEAD, with no uncommitted
                            changes on either side; otherwise "rebuild the stack" (T1-10)
     12. frontend stack e2e npm run test:e2e:stack: two customers in a real browser against the stack (T1-10;
                            STACK_E2E_* set for that step only; a skip fails). Skipped when step 11 failed.
    T1-3: it notes the commit it runs on (and fails if HEAD moves during the run), and stops the stack's containers
    before the backend tests (the suite uses its own SQL Server; step 8 starts them again), so the machine doesn't run
    out of memory. Run it from a terminal, not as a Claude Code background command (which is stopped when memory runs low).
    At the end: a table (PASS, FAIL or SKIP, counts where the step gives them, duration) and one overall result. Exit
    code 1 when any step failed. Each step's full output is saved under devstack/.logs/check-<time>/ (git-ignored);
    the path is printed. Invite links are masked in the logs, and no password is ever passed on a command line.

.PARAMETER SkipBackend
    Skip the backend tests (the cross-customer tests too).
.PARAMETER SkipFrontend
    Skip every frontend step (2 to 7).
.PARAMETER SkipE2E
    Skip only the Playwright e2e tests (the stack e2e too).
.PARAMETER SkipStack
    Skip the local stack (8 to 12).
.PARAMETER Quick
    No e2e tests, and the smoke check holds its stream for 5 seconds instead of 2 minutes.

.EXAMPLE
    .\devstack\scripts\check-all.ps1
    .\devstack\scripts\check-all.ps1 -Quick -SkipFrontend
#>
[CmdletBinding()]
param(
    [switch] $SkipBackend,
    [switch] $SkipFrontend,
    [switch] $SkipE2E,
    [switch] $SkipStack,
    [switch] $Quick
)
. (Join-Path $PSScriptRoot '_common.ps1')
$ErrorActionPreference = 'Continue'

$started = Get-Date
$logDir = Join-Path $script:DevstackRoot ('.logs\check-' + $started.ToString('yyyyMMdd-HHmmss'))
$null = New-Item -ItemType Directory -Path $logDir -Force
# The same PowerShell that runs this script runs the stack scripts (5.1 or 7).
$shell = (Get-Process -Id $PID).Path
$results = [System.Collections.Generic.List[object]]::new()

$config = $null
$configProblem = $null
if (-not ($SkipFrontend -and $SkipStack)) {
    try { $config = Get-StackConfig } catch { $configProblem = $_.Exception.Message }
}

function Hide-Secrets([string] $Line) {
    return ($Line -replace '#t=[A-Za-z0-9_-]{20,}', '#t=<token hidden>')
}

function Remove-Ansi([string] $Text) {
    return ($Text -replace "\x1B\[[0-9;?]*[A-Za-z]", '')
}

<# Runs one step: its output (standard output and error) goes to <log>.log, masked; PASS when its exit code is 0. #>
function Invoke-Step([string] $Name, [string] $Log, [scriptblock] $Action, [scriptblock] $Counts, [string] $SkipReason) {
    $file = Join-Path $logDir "$Log.log"
    if ($SkipReason) {
        $results.Add([pscustomobject]@{ Step = $Name; Result = 'SKIP'; Counts = $SkipReason; Seconds = 0; Log = $null })
        Write-Host ("   --  {0,-20} skipped ({1})" -f $Name, $SkipReason) -ForegroundColor DarkGray
        return
    }
    Write-Host ("   ..  {0,-20} running" -f $Name)
    $clock = [System.Diagnostics.Stopwatch]::StartNew()
    $passed = $false
    try {
        $global:LASTEXITCODE = 0
        & $Action *>&1 | ForEach-Object { Hide-Secrets "$_" } | Out-File -FilePath $file -Encoding utf8
        $passed = ($LASTEXITCODE -eq 0)
    } catch {
        Hide-Secrets "check-all: $($_.Exception.Message)" | Out-File -FilePath $file -Encoding utf8 -Append
    }
    $clock.Stop()
    $text = ''
    if (Test-Path -LiteralPath $file) { $text = Remove-Ansi (Get-Content -LiteralPath $file -Raw -Encoding utf8) }
    $summary = ''
    if ($Counts) { try { $summary = [string](& $Counts $text) } catch { $summary = '' } }
    $result = if ($passed) { 'PASS' } else { 'FAIL' }
    $results.Add([pscustomobject]@{ Step = $Name; Result = $result; Counts = $summary; Seconds = [int]$clock.Elapsed.TotalSeconds; Log = $file })
    $color = if ($passed) { 'Green' } else { 'Red' }
    Write-Host ("   {0}  {1,-20} {2} ({3:N0} s)" -f $(if ($passed) { 'ok' } else { '!!' }), $Name, $summary, $clock.Elapsed.TotalSeconds) -ForegroundColor $color
}

function Invoke-StackScript([string] $Script, [string[]] $Arguments) {
    & $shell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot $Script) @Arguments
}

Write-Step "check-all ($($started.ToString('yyyy-MM-dd HH:mm'))); logs in $logDir"
# T1-3: the commit tested, and the memory free at the start.
$commit = (& git -C $script:RepoRoot rev-parse HEAD 2>$null | Select-Object -First 1)
$dirty = @(& git -C $script:RepoRoot status --porcelain 2>$null | Where-Object { $_ -notmatch 'app-run\.log' })
$freeGb = [math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1MB, 1)
Write-Host "   commit $commit$(if ($dirty.Count) { " (plus $($dirty.Count) uncommitted change(s))" }); $freeGb GB memory free"
if ($config -and -not $SkipStack -and -not $SkipBackend) {
    # T1-3: the stack's containers (SQL Server, backend, frontend) aren't needed by the backend tests; step 8 restarts them.
    try {
        Set-ComposeEnvironment $config $null
        Invoke-Compose stop
        Write-Host '   stack containers stopped for the backend tests (stack up starts them again)'
    } catch {
        Write-Problem "Couldn't stop the stack's containers: $($_.Exception.Message)"
    }
}

# Totals from the Surefire reports written since $Since.
function Get-SurefireCounts([datetime] $Since) {
    $reports = Join-Path $script:RepoRoot 'target\surefire-reports'
    $t = 0; $f = 0; $e = 0; $s = 0
    foreach ($xml in Get-ChildItem -LiteralPath $reports -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTime -ge $Since }) {
        $suite = ([xml](Get-Content -LiteralPath $xml.FullName -Raw)).testsuite
        $t += [int]$suite.tests; $f += [int]$suite.failures; $e += [int]$suite.errors; $s += [int]$suite.skipped
    }
    "$t tests, $f failures, $e errors, $s skipped"
}

# --- 0. cross-customer tests (T1-10): fails when none ran, so a lost tag can't pass silently ---
$crossStart = Get-Date
Invoke-Step 'cross-customer tests' 'cross-customer-tests' {
    Push-Location $script:RepoRoot
    try { & .\mvnw.cmd test '-Dgroups=cross-customer' '-DfailIfNoTests=true' } finally { Pop-Location }
} { param($text) Get-SurefireCounts $crossStart } $(if ($SkipBackend) { '-SkipBackend' })

# --- 1. backend ---
$backendStart = Get-Date
Invoke-Step 'backend tests' 'backend-tests' {
    Push-Location $script:RepoRoot
    try { & .\mvnw.cmd test } finally { Pop-Location }
} { param($text) Get-SurefireCounts $backendStart } $(if ($SkipBackend) { '-SkipBackend' })

# --- 2-7. frontend ---
$frontendSkip = if ($SkipFrontend) { '-SkipFrontend' } else { $null }
$frontendRepo = if ($config) { $config.FrontendRepo } else { $null }

function Invoke-Frontend([scriptblock] $Command) {
    if ($configProblem) { throw $configProblem }
    if (-not (Test-Path -LiteralPath (Join-Path $frontendRepo 'package.json'))) { throw "No package.json in $frontendRepo (FRONTEND_REPO in devstack/stack.env)." }
    if (-not (Test-Path -LiteralPath (Join-Path $frontendRepo 'node_modules'))) { throw "No node_modules in ${frontendRepo}: run npm ci there first." }
    Push-Location $frontendRepo
    try { & $Command } finally { Pop-Location }
}

Invoke-Step 'frontend lint' 'frontend-lint' { Invoke-Frontend { & npm run lint } } {
    param($text)
    $errors = ([regex]::Matches($text, '\d+:\d+\s+Error:')).Count
    $warnings = ([regex]::Matches($text, '\d+:\d+\s+Warning:')).Count
    "$errors errors, $warnings warnings"
} $frontendSkip

Invoke-Step 'frontend types' 'frontend-tsc' { Invoke-Frontend { & npx tsc --noEmit } } {
    param($text)
    "$(([regex]::Matches($text, 'error TS\d+')).Count) type errors"
} $frontendSkip

Invoke-Step 'frontend unit' 'frontend-vitest' { Invoke-Frontend { & npm test } } {
    param($text)
    $m = [regex]::Match($text, '(?m)^\s*Tests\s+(.+?)\s*\((\d+)\)')
    if ($m.Success) { "$($m.Groups[1].Value.Trim()) of $($m.Groups[2].Value)" } else { '' }
} $frontendSkip

Invoke-Step 'frontend build' 'frontend-build' { Invoke-Frontend { & npm run build } } $null $frontendSkip

# The production build just made: no backend address, setting name or rewrite in it (T1-0d). Not skipped by -Quick or
# -SkipE2E: it reads the build, it doesn't make one.
Invoke-Step 'frontend check:build' 'frontend-check-build' { Invoke-Frontend { & npm run check:build } } {
    param($text)
    $m = [regex]::Match($text, '(\d+) files searched')
    if ($m.Success -and $text -match 'no backend address in the build') { "$($m.Groups[1].Value) files, no backend address" } else { '' }
} $frontendSkip

$e2eSkip = $frontendSkip
if (-not $e2eSkip -and $SkipE2E) { $e2eSkip = '-SkipE2E' }
if (-not $e2eSkip -and $Quick) { $e2eSkip = '-Quick' }
Invoke-Step 'frontend e2e' 'frontend-e2e' { Invoke-Frontend { & npm run test:e2e } } {
    param($text)
    $parts = [regex]::Matches($text, '(?m)^\s*(\d+) (passed|failed|flaky|skipped|did not run)') | ForEach-Object { "$($_.Groups[1].Value) $($_.Groups[2].Value)" }
    $parts -join ', '
} $e2eSkip

# --- 8-10. the local stack ---
$stackSkip = if ($SkipStack) { '-SkipStack' } else { $null }
Invoke-Step 'stack up' 'stack-up' {
    if ($configProblem) { throw $configProblem }
    Invoke-StackScript 'up.ps1' @()
} {
    param($text)
    $m = [regex]::Match($text, 'Up in (\d+) s'); if ($m.Success) { "up in $($m.Groups[1].Value) s" } else { '' }
} $stackSkip

Invoke-Step 'stack status' 'stack-status' { Invoke-StackScript 'status.ps1' @() } {
    param($text)
    if ($text -match 'All good\.') { 'all good' } else { $m = [regex]::Match($text, '(\d+) problem'); if ($m.Success) { "$($m.Groups[1].Value) problem(s)" } else { '' } }
} $stackSkip

$smokeArgs = @()
if ($Quick) { $smokeArgs = @('-Quick') }
Invoke-Step 'smoke' 'stack-smoke' { Invoke-StackScript 'smoke.ps1' $smokeArgs } {
    param($text)
    $m = [regex]::Match($text, 'Smoke check passed \((\d+) checks\)')
    if ($m.Success) { "$($m.Groups[1].Value) of $($m.Groups[1].Value) checks" }
    else { $m = [regex]::Match($text, 'FAILED: (\d+) of (\d+)'); if ($m.Success) { "$($m.Groups[1].Value) of $($m.Groups[2].Value) checks failed" } else { '' } }
} $stackSkip

# --- 11. the frontend the stack serves is the one under test (T1-10): its service worker's build id names the commit ---
Invoke-Step 'stack frontend build' 'stack-frontend-build' {
    if ($configProblem) { throw $configProblem }
    $url = (Get-CustomerUrl $config @(Get-ServedCustomers $config)[0]) + '/sw.js'
    $sw = (Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 30).Content
    $version = [regex]::Match($sw, 'const SW_VERSION = "([^"]+)"')
    if (-not $version.Success) { throw "rebuild the stack: $url has no SW_VERSION." }
    $served = $version.Groups[1].Value
    $head = & git -C $config.FrontendRepo rev-parse HEAD
    $repoDirty = @(& git -C $config.FrontendRepo status --porcelain).Count -gt 0
    Write-Output "stack serves $served; FRONTEND_REPO at $head$(if ($repoDirty) { ' with uncommitted changes' })"
    $id = [regex]::Match($served, '^.+?-([0-9a-f]{7,40}|nogit)(-dirty)?-[0-9a-z]+$')
    if (-not $id.Success -or $id.Groups[1].Value -eq 'nogit') { throw "rebuild the stack: it serves $served, which names no commit (up.ps1 passes the build id)." }
    if (-not $head.StartsWith($id.Groups[1].Value)) { throw "rebuild the stack: it serves $served, built from $($id.Groups[1].Value), but FRONTEND_REPO is at $head." }
    if ($id.Groups[2].Success) { throw "rebuild the stack: it serves $served, built from uncommitted changes." }
    if ($repoDirty) { throw "rebuild the stack: FRONTEND_REPO has uncommitted changes, so the stack can't be shown to serve them; commit them, then run check-all again." }
} {
    param($text)
    $m = [regex]::Match($text, 'stack serves (\S+);'); if ($m.Success) { $m.Groups[1].Value } else { '' }
} $stackSkip

# --- 12. two customers in a real browser, against the stack (T1-10; the frontend's e2e/stack-cross-customer.spec.ts) ---
$stackE2eSkip = $stackSkip
if (-not $stackE2eSkip -and $SkipFrontend) { $stackE2eSkip = '-SkipFrontend' }
if (-not $stackE2eSkip -and $SkipE2E) { $stackE2eSkip = '-SkipE2E' }
if (-not $stackE2eSkip -and $Quick) { $stackE2eSkip = '-Quick' }
if (-not $stackE2eSkip -and @($results | Where-Object { $_.Step -eq 'stack frontend build' -and $_.Result -ne 'PASS' }).Count) {
    $stackE2eSkip = 'the stack serves another frontend build'
}
Invoke-Step 'frontend stack e2e' 'frontend-stack-e2e' {
    $customers = @(Get-ServedCustomers $config)
    if ($customers.Count -lt 2) { throw 'The stack e2e needs multi mode (two customers at their own addresses).' }
    # The settings live in this step's environment only, and the password is never printed or on a command line.
    $settings = [ordered]@{
        STACK_E2E = '1'
        STACK_E2E_A_URL = Get-CustomerUrl $config $customers[0]; STACK_E2E_B_URL = Get-CustomerUrl $config $customers[1]
        STACK_E2E_A_EMAIL = Get-SmokeEmail $config $customers[0]; STACK_E2E_B_EMAIL = Get-SmokeEmail $config $customers[1]
        STACK_E2E_PASSWORD = $config.SmokePassword
    }
    try {
        foreach ($name in $settings.Keys) { Set-Item -Path "Env:$name" -Value $settings[$name] }
        $output = @(Invoke-Frontend { & npm run test:e2e:stack } 2>&1 | ForEach-Object { "$_" })
        $code = $LASTEXITCODE
        $output
        $text = Remove-Ansi ($output -join "`n")
        if ($code -eq 0 -and ($text -match '(?m)^\s*\d+ skipped' -or $text -notmatch '(?m)^\s*\d+ passed')) {
            Write-Output 'check-all: a skipped or empty stack e2e run counts as a failure.'
            $code = 1
        }
        $global:LASTEXITCODE = $code
    } finally {
        foreach ($name in $settings.Keys) { Remove-Item -Path "Env:$name" -ErrorAction SilentlyContinue }
    }
} {
    param($text)
    $parts = [regex]::Matches($text, '(?m)^\s*(\d+) (passed|failed|flaky|skipped|did not run)') | ForEach-Object { "$($_.Groups[1].Value) $($_.Groups[2].Value)" }
    $parts -join ', '
} $stackE2eSkip

# --- the result ---
$endCommit = (& git -C $script:RepoRoot rev-parse HEAD 2>$null | Select-Object -First 1)
if ($endCommit -ne $commit) {
    $results.Add([pscustomobject]@{ Step = 'same commit'; Result = 'FAIL'; Counts = "HEAD moved: $commit -> $endCommit"; Seconds = 0; Log = $null })
}
$failed = @($results | Where-Object { $_.Result -eq 'FAIL' })
$total = (Get-Date) - $started
Write-Host ''
Write-Host ('{0,-20} {1,-6} {2,-48} {3,8}' -f 'Step', 'Result', 'Counts', 'Time')
Write-Host ('{0,-20} {1,-6} {2,-48} {3,8}' -f '----', '------', '------', '----')
foreach ($row in $results) {
    $color = switch ($row.Result) { 'PASS' { 'Green' } 'FAIL' { 'Red' } default { 'DarkGray' } }
    $time = if ($row.Result -eq 'SKIP') { '' } else { '{0:N0} s' -f $row.Seconds }
    Write-Host ('{0,-20} {1,-6} {2,-48} {3,8}' -f $row.Step, $row.Result, $row.Counts, $time) -ForegroundColor $color
}
Write-Host ''
$overall = if ($failed.Count -eq 0) { 'PASS' } else { 'FAIL' }
$summaryLine = "Overall: $overall ({0} passed, {1} failed, {2} skipped) in {3:N0} min" -f @($results | Where-Object Result -eq 'PASS').Count, $failed.Count, @($results | Where-Object Result -eq 'SKIP').Count, $total.TotalMinutes
Write-Host $summaryLine -ForegroundColor $(if ($failed.Count -eq 0) { 'Green' } else { 'Red' })
foreach ($row in $failed) { Write-Host "  $($row.Step): $($row.Log)" -ForegroundColor Red }
Write-Host "Commit: $commit$(if ($dirty.Count) { " (plus $($dirty.Count) uncommitted change(s))" })"
Write-Host "Logs: $logDir"
[pscustomobject]@{ Commit = $commit; Uncommitted = $dirty.Count; Steps = @($results | Select-Object Step, Result, Counts, Seconds) } |
    ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $logDir 'summary.json') -Encoding utf8
if ($failed.Count -gt 0) { exit 1 }
exit 0
