<#
.SYNOPSIS
    T1-L0: tests for the devstack scripts that need no Docker (run by DevstackScriptsTest, or on its own:
    powershell -NoProfile -ExecutionPolicy Bypass -File devstack\scripts\tests\devstack.tests.ps1).

.DESCRIPTION
    Copies devstack/ to a temporary folder with its own stack.env (made-up values), replaces docker and Read-Host
    with functions that record their calls, and checks the settings rules, the customer-name rule, and that reset.ps1
    refuses a database that isn't the stack's and does nothing without the typed confirmation.
    Exits 0 when every case passes, 1 otherwise.
#>
$ErrorActionPreference = 'Stop'
$source = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$work = Join-Path ([IO.Path]::GetTempPath()) ("devstack-tests-" + [Guid]::NewGuid().ToString('N').Substring(0, 8))
$failures = 0

function Assert-Case([string] $Name, [bool] $Passed, [string] $Detail) {
    if ($Passed) { Write-Output "PASS  $Name" }
    else { Write-Output "FAIL  ${Name}: $Detail"; $script:failures++ }
}

function Write-StackEnv([string[]] $Lines) {
    Set-Content -LiteralPath (Join-Path $work 'stack.env') -Value $Lines -Encoding ascii
}

# Made-up values: these never reach a database.
$validEnv = @(
    '# comment', 'ACTIVE_CUSTOMER=', 'SQL_MODE=', "SQL_PASSWORD='Test-only-1'",
    ('JWT_SECRET=' + ('t' * 40)), 'HTTPS_PORT=', 'FRONTEND_REPO=', 'SMOKE_EMAIL=', '  # indented comment')

# Stand-ins: docker records its arguments and succeeds; Read-Host answers what the case sets.
$global:DevstackDockerCalls = [System.Collections.Generic.List[string]]::new()
$global:DevstackAnswer = ''
function global:docker { $global:DevstackDockerCalls.Add(($args -join ' ')); $global:LASTEXITCODE = 0 }
function global:Read-Host { param([string] $Prompt, [switch] $AsSecureString) $global:DevstackAnswer }

try {
    $null = New-Item -ItemType Directory -Path $work
    Copy-Item -Path (Join-Path $source 'compose.yaml'), (Join-Path $source 'Caddyfile') -Destination $work
    Copy-Item -Path (Join-Path $source 'scripts') -Destination (Join-Path $work 'scripts') -Recurse
    Write-StackEnv $validEnv

    # --- settings and names, through _common.ps1 dot-sourced from the copy ---
    . (Join-Path $work 'scripts\_common.ps1')
    $ErrorActionPreference = 'Stop'

    foreach ($good in 'beaconculinary', 'testcustomer', 'abc', 'a1b2c3', ('a' * 30)) {
        $ok = $true; try { Assert-CustomerName $good } catch { $ok = $false }
        Assert-Case "name accepted: $good" $ok 'refused'
    }
    foreach ($bad in 'ab', 'AB', 'Abc', '1abc', 'a-b', 'a_b', 'a b', 'abc;drop', 'abc]', ('a' * 31), 'master', 'tempdb', 'api', '') {
        $refused = $false; try { Assert-CustomerName $bad } catch { $refused = $true }
        Assert-Case "name refused: '$bad'" $refused 'accepted'
    }

    $config = Get-StackConfig
    Assert-Case 'defaults' ($config.ActiveCustomer -eq 'beaconculinary' -and $config.SqlMode -eq 'container' -and
        $config.SqlUser -eq 'sa' -and $config.SqlHostPort -eq 14333 -and $config.HttpsPort -eq 443 -and
        $config.PortSuffix -eq '' -and $config.FrontendMode -eq 'container' -and $config.StackName -eq 'beacon-devstack') 'unexpected defaults'
    Assert-Case 'quotes removed, value kept' ($config.SqlPassword -eq 'Test-only-1') 'quoted value not read'
    Assert-Case 'frontend repository defaults to the sibling folder' ($config.FrontendRepo -eq
        (Join-Path (Split-Path -Parent (Split-Path -Parent $work)) 'beacon-culinary-pos-front-end')) $config.FrontendRepo
    Assert-Case '-Customer overrides ACTIVE_CUSTOMER' ((Get-StackConfig -Customer 'testcustomer').ActiveCustomer -eq 'testcustomer') 'not overridden'

    Write-StackEnv ($validEnv + 'HTTPS_PORT=8443')
    $config = Get-StackConfig
    Assert-Case 'another HTTPS port goes into every address' ((Get-CustomerUrl $config 'testcustomer') -eq 'https://testcustomer.pos.localhost:8443') (Get-CustomerUrl $config 'testcustomer')

    $refusals = @{
        'no SQL password'          = @('SQL_PASSWORD=', ('JWT_SECRET=' + ('t' * 40)))
        'SQL password too short'   = @('SQL_PASSWORD=Ab1!xyz', ('JWT_SECRET=' + ('t' * 40)))
        'SQL password digits only' = @('SQL_PASSWORD=12345678', ('JWT_SECRET=' + ('t' * 40)))
        'short JWT secret'         = @("SQL_PASSWORD=Test-only-1", 'JWT_SECRET=short')
        'unknown SQL mode'         = @("SQL_PASSWORD=Test-only-1", ('JWT_SECRET=' + ('t' * 40)), 'SQL_MODE=cloud')
        'container mode needs sa'  = @("SQL_PASSWORD=Test-only-1", ('JWT_SECRET=' + ('t' * 40)), 'SQL_USER=app')
        'bad active customer'      = @("SQL_PASSWORD=Test-only-1", ('JWT_SECRET=' + ('t' * 40)), 'ACTIVE_CUSTOMER=Beacon-Culinary')
        'unknown frontend mode'    = @("SQL_PASSWORD=Test-only-1", ('JWT_SECRET=' + ('t' * 40)), 'FRONTEND_MODE=static')
    }
    foreach ($case in $refusals.Keys) {
        Write-StackEnv $refusals[$case]
        $refused = $false; try { $null = Get-StackConfig } catch { $refused = $true }
        Assert-Case "settings refused: $case" $refused 'accepted'
    }
    Remove-Item -LiteralPath (Join-Path $work 'stack.env')
    $refused = $false; try { $null = Get-StackConfig } catch { $refused = $_.Exception.Message -like '*stack.env is missing*' }
    Assert-Case 'missing stack.env is explained' $refused 'no clear message'
    Write-StackEnv $validEnv

    $refused = $false; try { Remove-StackDatabase $config 'master' } catch { $refused = $true }
    Assert-Case 'dropping master is refused' ($refused -and -not ($global:DevstackDockerCalls | Where-Object { $_ -like '*DROP*' })) 'not refused'

    Add-ManagedCustomer 'secondcafe'
    $managed = Get-ManagedCustomers
    Assert-Case 'new customers are remembered' (($managed -join ',') -eq 'beaconculinary,testcustomer,secondcafe') ($managed -join ',')

    # --- a real native docker that writes to standard error (what Windows PowerShell 5.1 turns into errors) ---
    $fakeBin = Join-Path $work 'fakebin'
    $null = New-Item -ItemType Directory -Path $fakeBin
    Set-Content -LiteralPath (Join-Path $fakeBin 'docker.cmd') -Encoding ascii -Value @(
        '@echo Unable to find image ''example:1'' locally 1>&2', '@echo pulled-and-ran', '@exit /b 0')
    Remove-Item Function:\docker
    $savedPath = $env:Path
    $env:Path = "$fakeBin;$env:Path"
    try {
        $ErrorActionPreference = 'Stop'
        $lines = $null; $threw = $null
        try { $lines = Invoke-Docker @('run', 'example:1') } catch { $threw = $_.Exception.Message }
        Assert-Case 'docker writing to standard error does not stop the scripts' ($null -eq $threw -and $LASTEXITCODE -eq 0 -and
            ($lines -join ' ') -like '*pulled-and-ran*') "threw: $threw"
    } finally {
        $env:Path = $savedPath
        function global:docker { $global:DevstackDockerCalls.Add(($args -join ' ')); $global:LASTEXITCODE = 0 }
    }

    # --- T1-1: the customers' login secrets and the platform settings ---
    $made = 'Made-Up-Pw1!'
    Write-StackEnv ($validEnv + @('SECRET_BEACONCULINARY_DB_LOGIN=pos_beaconculinary', "SECRET_BEACONCULINARY_DB_PASSWORD=$made",
        'SECRET_TESTCUSTOMER_DB_LOGIN=sa', 'SECRET_TESTCUSTOMER_DB_PASSWORD=weak'))
    $config = Get-StackConfig
    $secrets = Get-CustomerSecretValues $config 'beaconculinary'
    Assert-Case 'a customer''s login and password come from SECRET_<NAME>_DB_*' ($secrets.Login -eq 'pos_beaconculinary' -and
        $secrets.Password -eq $made -and $secrets.LoginSecret -eq 'beaconculinary-db-login' -and
        $secrets.PasswordSecret -eq 'beaconculinary-db-password') 'wrong values'
    $message = ''; try { $null = Get-CustomerSecretValues $config 'testcustomer' } catch { $message = $_.Exception.Message }
    Assert-Case 'sa is refused as a customer login' ($message -like '*SECRET_TESTCUSTOMER_DB_LOGIN must be a login of its own*') $message
    $message = ''; try { $null = Get-CustomerSecretValues $config 'secondcafe' } catch { $message = $_.Exception.Message }
    Assert-Case 'a missing customer secret names the setting' ($message -like '*SECRET_SECONDCAFE_DB_LOGIN is not set*') $message
    foreach ($bad in 'weak', 'alllowercase', "Quote'Pw1!") {
        $message = ''; try { Assert-LoginPassword 'SECRET_X_DB_PASSWORD' $bad } catch { $message = $_.Exception.Message }
        Assert-Case "a weak or quoted login password is refused, naming the setting only" ($message -like 'SECRET_X_DB_PASSWORD must*' -and
            -not $message.Contains($bad)) $message
    }
    $message = ''; try { Assert-PlatformSettings $config } catch { $message = $_.Exception.Message }
    Assert-Case 'a missing platform password is reported' ($message -like 'PLATFORM_DB_PASSWORD is not set*') $message
    Write-StackEnv ($validEnv + 'PLATFORM_DATABASE=Pos-Platform')
    $refused = $false; try { $null = Get-StackConfig } catch { $refused = $_.Exception.Message -like 'PLATFORM_DATABASE must*' }
    Assert-Case 'a bad platform database name is refused' $refused 'accepted'
    Write-StackEnv ($validEnv + 'TENANCY_MODE=both')
    $refused = $false; try { $null = Get-StackConfig } catch { $refused = $_.Exception.Message -like 'TENANCY_MODE must*' }
    Assert-Case 'an unknown tenancy mode is refused' $refused 'accepted'
    Write-StackEnv $validEnv
    $config = Get-StackConfig
    Assert-Case 'tenancy defaults to multi (T1-3) and the platform database to pos_platform' ($config.TenancyMode -eq 'multi' -and
        $config.PlatformDatabase -eq 'pos_platform') "$($config.TenancyMode) $($config.PlatformDatabase)"

    # The login's password goes to sqlcmd as an environment variable named on the docker command line, never as a value.
    $global:DevstackDockerCalls.Clear()
    Set-DatabaseLogin $config 'pos_beaconculinary' $made 'beaconculinary'
    $calls = $global:DevstackDockerCalls -join "`n"
    Assert-Case 'a login''s password never appears on a docker command line' (-not $calls.Contains($made) -and
        $calls.Contains('-e DEVSTACK_LOGIN_PASSWORD') -and $calls.Contains('$(DEVSTACK_LOGIN_PASSWORD)')) 'the password or the variable is wrong'
    Assert-Case 'the login''s password is not left in the environment' ($null -eq [Environment]::GetEnvironmentVariable('DEVSTACK_LOGIN_PASSWORD')) 'still set'

    # --- check-all.ps1 with every step skipped: passes, reports each step, keeps a log folder ---
    $checkAll = Join-Path $work 'scripts\check-all.ps1'
    $shell = (Get-Process -Id $PID).Path
    $lines = @(& $shell -NoProfile -ExecutionPolicy Bypass -File $checkAll -SkipBackend -SkipFrontend -SkipStack *>&1 | ForEach-Object { "$_" })
    $code = $LASTEXITCODE
    $skips = @($lines | Where-Object { $_ -match '^\S.*\sSKIP\s' }).Count
    $logs = @(Get-ChildItem -LiteralPath (Join-Path $work '.logs') -Directory -Filter 'check-*' -ErrorAction SilentlyContinue)
    Assert-Case 'check-all with everything skipped passes and lists all ten steps' ($code -eq 0 -and $skips -eq 10 -and
        ($lines -join "`n") -match 'Overall: PASS' -and $logs.Count -eq 1) "exit $code, $skips skipped rows, $($logs.Count) log folder(s)"

    # --- reset.ps1, run as a script from the copy ---
    # T1-1: reset checks the platform and login secrets before it asks; made-up values let it get that far.
    Write-StackEnv ($validEnv + @('PLATFORM_DB_PASSWORD=Made-Up-Pw2!', 'SECRET_BEACONCULINARY_DB_LOGIN=pos_beaconculinary',
        'SECRET_BEACONCULINARY_DB_PASSWORD=Made-Up-Pw3!', 'SECRET_TESTCUSTOMER_DB_LOGIN=pos_testcustomer',
        'SECRET_TESTCUSTOMER_DB_PASSWORD=Made-Up-Pw4!', 'SECRET_SECONDCAFE_DB_LOGIN=pos_secondcafe',
        'SECRET_SECONDCAFE_DB_PASSWORD=Made-Up-Pw5!'))
    $reset = Join-Path $work 'scripts\reset.ps1'
    $global:DevstackDockerCalls.Clear()
    $threw = $false
    try { & $reset -Customer 'othercustomer' *> $null } catch { $threw = $_.Exception.Message -like "*isn't one of the stack's customers*" }
    Assert-Case 'reset refuses a database that is not the stack''s' ($threw -and -not ($global:DevstackDockerCalls | Where-Object { $_ -like 'run *' -or $_ -like '*compose*stop*' })) 'not refused'

    $global:DevstackDockerCalls.Clear()
    $global:DevstackAnswer = 'reset'
    $global:LASTEXITCODE = 0
    try { & $reset *> $null; $code = $LASTEXITCODE } catch { $code = "threw: $($_.Exception.Message)" }
    $touched = $global:DevstackDockerCalls | Where-Object { $_ -like 'run *' -or $_ -like '*compose*stop*' }
    Assert-Case 'reset does nothing without RESET typed exactly' ($code -eq 1 -and -not $touched) "exit $code; docker: $($touched -join ' | ')"
} finally {
    Remove-Item Function:\docker, Function:\Read-Host -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}

if ($failures -gt 0) { Write-Output "$failures case(s) failed"; exit 1 }
Write-Output 'All cases passed'
exit 0
