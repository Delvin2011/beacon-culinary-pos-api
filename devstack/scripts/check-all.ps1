<#
.SYNOPSIS
    T1-1: checks everything before a slice is merged (until the GitHub pipelines exist): the backend's tests, the
    frontend's lint, types, unit tests, build and e2e tests, and the local stack's smoke check.

.DESCRIPTION
    Every step runs, whatever happened before it, and each is reported:
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
    At the end: a table (PASS, FAIL or SKIP, counts where the step gives them, duration) and one overall result. Exit
    code 1 when any step failed. Each step's full output is saved under devstack/.logs/check-<time>/ (git-ignored);
    the path is printed. Invite links are masked in the logs, and no password is ever passed on a command line.

.PARAMETER SkipBackend
    Skip the backend tests.
.PARAMETER SkipFrontend
    Skip every frontend step (2 to 7).
.PARAMETER SkipE2E
    Skip only the Playwright e2e tests.
.PARAMETER SkipStack
    Skip the local stack (8 to 10).
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

# --- 1. backend ---
$backendStart = Get-Date
Invoke-Step 'backend tests' 'backend-tests' {
    Push-Location $script:RepoRoot
    try { & .\mvnw.cmd test } finally { Pop-Location }
} {
    param($text)
    $reports = Join-Path $script:RepoRoot 'target\surefire-reports'
    $t = 0; $f = 0; $e = 0; $s = 0
    foreach ($xml in Get-ChildItem -LiteralPath $reports -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTime -ge $backendStart }) {
        $suite = ([xml](Get-Content -LiteralPath $xml.FullName -Raw)).testsuite
        $t += [int]$suite.tests; $f += [int]$suite.failures; $e += [int]$suite.errors; $s += [int]$suite.skipped
    }
    "$t tests, $f failures, $e errors, $s skipped"
} $(if ($SkipBackend) { '-SkipBackend' })

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

# --- the result ---
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
Write-Host "Logs: $logDir"
$results | Select-Object Step, Result, Counts, Seconds | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $logDir 'summary.json') -Encoding utf8
if ($failed.Count -gt 0) { exit 1 }
exit 0
