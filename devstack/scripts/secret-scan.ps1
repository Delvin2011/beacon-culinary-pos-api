<#
.SYNOPSIS
    T1-L0: looks for secrets in what this branch commits. Prints where a finding is, never its value.

.DESCRIPTION
    1. Every committed *.example file holds names only: each KEY=... line has an empty value.
    2. The lines this branch adds (git diff <Base>...HEAD) are matched against secret patterns: passwords and keys
       given a value, connection strings with a password, private keys, JWTs, long Base64 and hex strings next to a
       secret-like name. Test fixtures with made-up values are allowed by -Allow patterns (path globs).
    3. With Docker: gitleaks (pinned) over the branch's commits, findings redacted.
    git-ignored files (.env, devstack/stack.env) are never committed, so they are not scanned.

.PARAMETER Base
    The branch point to compare with (default main).

.PARAMETER SkipGitleaks
    Patterns only, without Docker.
#>
[CmdletBinding()]
param(
    [string] $Base = 'main',
    [switch] $SkipGitleaks,
    # Files whose matches are known test fixtures (made-up values), as git path globs.
    [string[]] $Allow = @('src/test/*')
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $repo
$findings = 0
try {
    function Report([string] $Where, [string] $What) {
        Write-Host "   !!  ${Where}: $What" -ForegroundColor Yellow
        $script:findings++
    }

    Write-Host '== 1. Example files hold names only' -ForegroundColor Cyan
    $examples = @(git ls-files -- '*.example' '*.env.example' | Sort-Object -Unique)
    foreach ($file in $examples) {
        $number = 0
        foreach ($line in Get-Content -LiteralPath $file) {
            $number++
            $trimmed = $line.Trim()
            if ($trimmed -eq '' -or $trimmed.StartsWith('#')) { continue }
            if ($trimmed -match '^[A-Za-z_][A-Za-z0-9_]*=(.*)$' -and $Matches[1].Trim() -ne '') {
                Report "${file}:$number" 'a value is set (example files must hold names only)'
            }
        }
    }
    Write-Host "   ok  $($examples.Count) example file(s) checked"

    Write-Host "== 2. Lines added since $Base" -ForegroundColor Cyan
    $patterns = [ordered]@{
        'private key'                    = '-----BEGIN [A-Z ]*PRIVATE KEY-----'
        'JWT'                            = 'eyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}'
        # Literal values only: code that names a secret (this.password = password; Get-Setting ... 'SQL_PASSWORD')
        # and placeholders (${...}, $env:..., <...>) are not findings.
        'connection string password'     = '(?i)\b(password|pwd)=[^;''"\s$<{]{4,};'
        # A value needs a lower-case letter or a digit, so placeholders such as "REQUIRED" or CHANGE_ME pass.
        'secret name with a quoted value' = '(?i)(password|secret|signing-?key|api_?key)[A-Za-z0-9_.-]*["'']?\s*[=:]\s*["''](?=(?-i:[^"'']*[a-z0-9]))[^"''\s${}<]{8,}["'']'
        # Statements (ending in ";") are code, not settings.
        'secret setting with a value'    = '(?i)^\s*[A-Z0-9_.-]*(PASSWORD|SECRET|SIGNINGKEY|SIGNING-KEY|API_?KEY)[A-Z0-9_.-]*\s*[=:]\s*(?=(?-i:[^\s]*[a-z0-9]))[^\s"''${}<#;]{8,}\s*$'
        'long Base64 or hex value'       = '(?i)(secret|key|password|token)[^=:]{0,20}[=:]\s*["'']?([A-Za-z0-9+/]{40,}={0,2}|[0-9a-f]{48,})'
        'Azure storage or SAS key'       = '(AccountKey=[A-Za-z0-9+/=]{20,}|[?&]sig=[A-Za-z0-9%+/=]{20,})'
    }
    $diff = git diff --unified=0 --no-color "$Base...HEAD"
    if ($LASTEXITCODE -ne 0) { throw "git diff $Base...HEAD failed." }
    $file = $null
    $lineNumber = 0
    $scanned = 0
    foreach ($line in $diff) {
        if ($line.StartsWith('+++ ')) { $file = $line.Substring(4) -replace '^b/', ''; continue }
        if ($line -match '^@@ -\d+(?:,\d+)? \+(\d+)') { $lineNumber = [int]$Matches[1]; continue }
        if (-not $line.StartsWith('+') -or $line.StartsWith('+++')) { continue }
        $text = $line.Substring(1)
        $scanned++
        $allowed = $false
        foreach ($glob in $Allow) { if ($file -like $glob) { $allowed = $true } }
        if (-not $allowed) {
            foreach ($name in $patterns.Keys) {
                if ($text -match $patterns[$name]) { Report "${file}:$lineNumber" $name }
            }
        }
        $lineNumber++
    }
    Write-Host "   ok  $scanned added line(s) scanned"

    if (-not $SkipGitleaks) {
        Write-Host "== 3. gitleaks over the commits since $Base" -ForegroundColor Cyan
        if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
            Write-Host '   --  skipped: Docker is not installed (-SkipGitleaks to silence this)' -ForegroundColor Yellow
        } else {
            $range = "$Base..HEAD"
            # In a script block with its own error preference: Windows PowerShell 5.1 would stop on docker's
            # standard error ("Unable to find image ... locally" before the first pull).
            $output = & { $ErrorActionPreference = 'Continue'; & docker run --rm -v "${repo}:/repo:ro" --entrypoint sh zricethezav/gitleaks:v8.21.2 -c `
                "git config --global --add safe.directory /repo && gitleaks git /repo --redact --no-banner --log-opts='$range'" 2>&1 }
            $code = $LASTEXITCODE
            $output | ForEach-Object { Write-Host "       $_" }
            if ($code -eq 1) { Report 'gitleaks' 'findings above (values redacted)' }
            elseif ($code -ne 0) { throw "gitleaks failed to run (exit code $code)." }
            else { Write-Host '   ok  no leaks found' }
        }
    }
} finally {
    Pop-Location
}

Write-Host ''
if ($findings -eq 0) { Write-Host 'No secrets found.' -ForegroundColor Green; exit 0 }
Write-Host "$findings finding(s). Remove the value from the commit (and rotate it if it was real)." -ForegroundColor Red
exit 1
