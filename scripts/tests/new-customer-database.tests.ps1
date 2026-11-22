<#
.SYNOPSIS
    T0-7: tests for scripts/new-customer-database.ps1 (run by NewCustomerDatabaseScriptTest, or on its own:
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\new-customer-database.tests.ps1).

.DESCRIPTION
    Runs the script in this process with java and Read-Host replaced by functions, so nothing connects to a
    database and nothing is typed. For every way the script ends, checks the exit code and that a password the
    script asked for is gone from the environment afterwards, while a password the caller had set is left alone.
    Exits 0 when every case passes, 1 otherwise.
#>
$ErrorActionPreference = 'Stop'
$script = Join-Path $PSScriptRoot '..\new-customer-database.ps1'
$jar = [IO.Path]::GetTempFileName()
$failures = 0

# Stand-ins: "java" exits with the next code in the list; "Read-Host" types a password.
$global:T07JavaExitCodes = @()
$global:T07JavaCalls = 0
function global:java {
    $global:T07JavaCalls++
    $code = $global:T07JavaExitCodes[0]
    $global:T07JavaExitCodes = @($global:T07JavaExitCodes | Select-Object -Skip 1)
    $global:LASTEXITCODE = $code
}
function global:Read-Host {
    param([switch] $AsSecureString, [string] $Prompt)
    ConvertTo-SecureString 'typed-test-password' -AsPlainText -Force
}

function Invoke-Case {
    param([string] $Name, [int[]] $JavaExitCodes, [string] $JarPath, [string] $PresetPassword,
          $ExpectedExit, [int] $ExpectedJavaCalls)
    $env:SPRING_DATASOURCE_URL = 'jdbc:sqlserver://t07-script-test.invalid:1433;databaseName=none'
    $env:SPRING_DATASOURCE_USERNAME = 't07-test'
    if ($PresetPassword) { $env:SPRING_DATASOURCE_PASSWORD = $PresetPassword }
    else { Remove-Item Env:SPRING_DATASOURCE_PASSWORD -ErrorAction SilentlyContinue }
    $global:T07JavaExitCodes = $JavaExitCodes
    $global:T07JavaCalls = 0
    $global:LASTEXITCODE = $null

    $exit = $null
    try {
        & $script -Jar $JarPath -Name 'T07 Test' -Email 't07@example.test' *> $null
        $exit = $LASTEXITCODE
    } catch {
        $exit = 'threw'
    }

    $problems = @()
    if ("$exit" -ne "$ExpectedExit") { $problems += "exit was [$exit], expected [$ExpectedExit]" }
    if ($global:T07JavaCalls -ne $ExpectedJavaCalls) { $problems += "java ran $($global:T07JavaCalls) time(s), expected $ExpectedJavaCalls" }
    $after = $env:SPRING_DATASOURCE_PASSWORD
    if ($PresetPassword) {
        if ($after -ne $PresetPassword) { $problems += 'the password the caller set was changed or removed' }
    } elseif ($after) {
        $problems += 'the password the script asked for is still in the environment'
    }

    if ($problems.Count -eq 0) {
        Write-Output "PASS  $Name"
    } else {
        Write-Output "FAIL  ${Name}: $($problems -join '; ')"
        $script:failures++
    }
}

try {
    Invoke-Case -Name 'database step fails' -JavaExitCodes @(4) -JarPath $jar -ExpectedExit 4 -ExpectedJavaCalls 1
    Invoke-Case -Name 'database refused (not empty or another collation)' -JavaExitCodes @(3) -JarPath $jar -ExpectedExit 3 -ExpectedJavaCalls 1
    Invoke-Case -Name 'first-admin step fails' -JavaExitCodes @(0, 3) -JarPath $jar -ExpectedExit 3 -ExpectedJavaCalls 2
    Invoke-Case -Name 'both steps succeed' -JavaExitCodes @(0, 0) -JarPath $jar -ExpectedExit 0 -ExpectedJavaCalls 2
    Invoke-Case -Name 'pre-check fails (no jar) before the password is asked' -JavaExitCodes @() -JarPath ($jar + '.missing') -ExpectedExit 'threw' -ExpectedJavaCalls 0
    Invoke-Case -Name 'caller-set password is left alone on failure' -JavaExitCodes @(4) -JarPath $jar -PresetPassword 'caller-set' -ExpectedExit 4 -ExpectedJavaCalls 1
    Invoke-Case -Name 'caller-set password is left alone on success' -JavaExitCodes @(0, 0) -JarPath $jar -PresetPassword 'caller-set' -ExpectedExit 0 -ExpectedJavaCalls 2
} finally {
    Remove-Item Function:\java, Function:\Read-Host -ErrorAction SilentlyContinue
    Remove-Item Env:SPRING_DATASOURCE_URL, Env:SPRING_DATASOURCE_USERNAME, Env:SPRING_DATASOURCE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $jar -ErrorAction SilentlyContinue
}

if ($failures -gt 0) {
    Write-Output "$failures case(s) failed"
    exit 1
}
Write-Output 'All cases passed'
exit 0
