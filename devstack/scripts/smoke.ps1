<#
.SYNOPSIS
    T1-L0: the end-to-end check through the gateway, as a browser would call it.

.DESCRIPTION
    T1-3: in multi mode (the stack's default) it checks beaconculinary and testcustomer in one run, each at its own
    https://<customer>.pos.localhost; in single mode the active customer only.
    At each customer:
      1. /api/health is UP through the proxy, with an X-Request-Id header.
      2. Password sign-in (beaconculinary: SMOKE_EMAIL; the others: their smoke account smoke@<name>.test, made by up.ps1;
         SMOKE_PASSWORD) sets the refresh cookie Secure, HttpOnly, SameSite=None, Path=/api/auth/refresh, and
         /api/auth/refresh with that cookie works.
      3. Our own X-Request-Id comes back, and the backend's log line for it shows host=<customer>.pos.localhost.
      4. The kitchen stream, opened through the proxy, delivers the event of an order placed through the API within
         5 s, stays open (2 minutes for the first customer, -Quick: 5 s; 5 s for the others), and still delivers the
         next event after that.
    Multi mode, across customers: while one customer's order is placed, a kitchen stream held open at every other
    customer receives no event at all; and an address no customer has (Host: nobody.pos.localhost, through Caddy)
    answers 404 with an empty body on /api/health and on /api/public/board/today.
    The order: one kitchen item from today's menu (READY, kitchen station), paid in cash, on the till's open shift
    (opened with a zero float and closed again when the check opened it). When the menu has none, the check adds an
    always-on light meal, "Smoke check item", through the admin API, as an admin would; later runs reuse it. The sale
    and the item stay in the database until reset.ps1.
    Tokens, cookies and passwords are never printed. Exits 0 when every check passes, 1 otherwise.

.PARAMETER Customer
    Check only this customer.

.PARAMETER Quick
    Hold the first stream for 5 seconds instead of 2 minutes.

.PARAMETER QuietHoldMinutes
    Also hold a second, quiet kitchen stream (no orders) at the first customer for this many minutes through the
    gateway, and require the keepalive comments to keep it from going silent for 60 s or more (Front Door closes
    connections idle for about 4 minutes). Not part of the default run: smoke.ps1 -QuietHoldMinutes 6.
#>
[CmdletBinding()]
param(
    [string] $Customer,
    [switch] $Quick,
    [ValidateRange(0, 60)] [int] $QuietHoldMinutes = 0
)
. (Join-Path $PSScriptRoot '_common.ps1')
Add-Type -AssemblyName System.Net.Http

$config = Get-StackConfig -Customer $Customer
Assert-Docker
if (-not $config.SmokeEmail -or -not $config.SmokePassword) { throw 'Set SMOKE_EMAIL and SMOKE_PASSWORD (an ADMIN account) in devstack/stack.env.' }
$multi = $config.TenancyMode -eq 'multi'
$customers = @($config.ActiveCustomer)
if ($multi -and -not $Customer) { $customers = @($script:FullHistoryCustomer, $script:CleanCustomer) }
$backendContainer = "$($config.StackName)-backend-1"
$holdSeconds = 120
if ($Quick) { $holdSeconds = 5 }

$results = [System.Collections.Generic.List[object]]::new()
function Add-Result([string] $Name, [bool] $Passed, [string] $Detail) {
    $results.Add([pscustomobject]@{ Name = $Name; Passed = $Passed; Detail = $Detail })
    if ($Passed) { Write-Ok "$Name  $Detail" } else { Write-Problem "$Name  $Detail" }
}

function New-HttpClient([TimeSpan] $Timeout) {
    $handler = [System.Net.Http.HttpClientHandler]::new()
    # Cookies are read and sent by hand, so the check sees exactly what the browser would get.
    $handler.UseCookies = $false
    $handler.AllowAutoRedirect = $false
    $client = [System.Net.Http.HttpClient]::new($handler)
    $client.Timeout = $Timeout
    return $client
}

$client = New-HttpClient ([TimeSpan]::FromSeconds(30))
$streamClient = New-HttpClient ([System.Threading.Timeout]::InfiniteTimeSpan)

<# One request to $script:base (the customer being checked). Returns status, headers, body text and parsed JSON. #>
function Invoke-Api([string] $Method, [string] $Path, $Body, [string] $Token, [hashtable] $Headers, [string] $HostHeader) {
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), "$script:base$Path")
    if ($Token) { $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token) }
    if ($HostHeader) { $request.Headers.Host = $HostHeader }
    if ($Headers) { foreach ($key in $Headers.Keys) { $null = $request.Headers.TryAddWithoutValidation($key, [string]$Headers[$key]) } }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 6 -Compress
        $request.Content = [System.Net.Http.StringContent]::new($json, [Text.Encoding]::UTF8, 'application/json')
    }
    $response = $client.SendAsync($request).GetAwaiter().GetResult()
    $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $headers = @{}
    foreach ($header in $response.Headers) { $headers[$header.Key] = @($header.Value) }
    $parsed = $null
    if ($text -and ($text.TrimStart().StartsWith('{') -or $text.TrimStart().StartsWith('['))) {
        try { $parsed = $text | ConvertFrom-Json } catch { $parsed = $null }
    }
    return [pscustomobject]@{ Status = [int]$response.StatusCode; Headers = $headers; Text = $text; Json = $parsed }
}

<# A short, safe description of a failed response: status and the error code or message, never a token. #>
function Describe($Response) {
    $detail = ''
    if ($Response.Json -and ($Response.Json.PSObject.Properties.Name -contains 'code')) { $detail = " $($Response.Json.code)" }
    elseif ($Response.Json -and ($Response.Json.PSObject.Properties.Name -contains 'message')) { $detail = " $($Response.Json.message)" }
    return "HTTP $($Response.Status)$detail"
}

<# Opens a kitchen stream at $Base; returns its reader and pending line, or $null with the reason in $script:streamProblem. #>
function Open-KitchenStream([string] $Base, [string] $Token) {
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Get, "$Base/api/kitchen/orders/stream")
    $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token)
    $request.Headers.Accept.ParseAdd('text/event-stream')
    $task = $streamClient.SendAsync($request, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead)
    if (-not $task.Wait(10000)) { $script:streamProblem = 'no response headers within 10 s (buffered?)'; return $null }
    if ([int]$task.Result.StatusCode -ne 200) { $script:streamProblem = "HTTP $([int]$task.Result.StatusCode)"; return $null }
    $stream = $task.Result.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
    $reader = [System.IO.StreamReader]::new($stream)
    return [pscustomobject]@{ Stream = $stream; Reader = $reader; Pending = $reader.ReadLineAsync() }
}

function Close-KitchenStream($S) {
    if ($S) { $S.Reader.Dispose(); $S.Stream.Dispose() }
}

<#
    Waits up to $Seconds for a data line (one that mentions $OrderId, or any when $OrderId is 0); returns the seconds it
    took, -1 when none came, -2 when the stream closed.
#>
function Wait-StreamEvent($S, [long] $OrderId, [double] $Seconds, [datetime] $From) {
    $until = $From.AddSeconds($Seconds)
    while ($true) {
        $left = [int][Math]::Max(0, ($until - (Get-Date)).TotalMilliseconds)
        if (-not $S.Pending.Wait($left)) { return -1 }
        $line = $S.Pending.Result
        if ($null -eq $line) { return -2 }
        $S.Pending = $S.Reader.ReadLineAsync()
        # The kitchen event names the order "orderId" (inside "order"); "id" is accepted too.
        if ($line.StartsWith('data:') -and ($OrderId -eq 0 -or $line -match "`"(orderId|id)`"\s*:\s*$OrderId\b")) {
            return ((Get-Date) - $From).TotalSeconds
        }
    }
}

<# A kitchen item on today's menu that the till can sell (READY, kitchen station; beverages go to the bar). #>
function Select-KitchenItem($Menu) {
    if ($Menu.Status -ne 200 -or -not $Menu.Json) { return $null }
    return @($Menu.Json.options) | Where-Object {
        $_ -and $_.status -eq 'READY' -and -not $_.outToday -and $_.price -gt 0 -and
        @('SET_MEALS', 'LIGHT_MEALS') -contains $_.group -and
        ($null -eq $_.portionsRemaining -or $_.portionsRemaining -gt 0)
    } | Select-Object -First 1
}
function Get-CategoryTree($Nodes) {
    foreach ($node in @($Nodes)) {
        if (-not $node) { continue }
        $node
        if ($node.PSObject.Properties.Name -contains 'children') { Get-CategoryTree $node.children }
    }
}

$tokens = [ordered]@{}
$openedShifts = @{}
$streams = [System.Collections.Generic.List[object]]::new()
try {
    # --- 1-3, at each customer: health, sign-in, refresh, request id, log line ---
    foreach ($name in $customers) {
        $script:base = Get-CustomerUrl $config $name
        $label = if ($customers.Count -gt 1) { "${name}: " } else { '' }
        Write-Step "Smoke check against $script:base"
        $expectedHost = "$name.pos.localhost$($config.PortSuffix)"
        try {
            $health = Invoke-Api 'GET' '/api/health'
            $up = $health.Status -eq 200 -and $health.Json -and $health.Json.status -eq 'UP'
            Add-Result "${label}health" ($up -and $health.Headers.ContainsKey('X-Request-Id')) ("$(Describe $health), X-Request-Id " + $(if ($health.Headers.ContainsKey('X-Request-Id')) { 'present' } else { 'missing' }))
        } catch {
            Add-Result "${label}health" $false "no answer: $($_.Exception.GetBaseException().Message) (status.ps1 checks the hosts file and certificate)"
            throw 'Stopping: the gateway is not reachable.'
        }

        $requestId = 'smoke-' + [Guid]::NewGuid().ToString('N').Substring(0, 20)
        $since = (Get-Date).ToUniversalTime().AddSeconds(-5).ToString('yyyy-MM-ddTHH:mm:ssZ')
        $email = Get-SmokeEmail $config $name
        $login = Invoke-Api 'POST' '/api/auth/login' @{ email = $email; password = $config.SmokePassword } $null @{ 'X-Request-Id' = $requestId }
        if ($login.Status -ne 200 -or -not $login.Json -or -not $login.Json.token) {
            Add-Result "${label}sign-in" $false "$(Describe $login) as $email (beaconculinary: SMOKE_EMAIL/SMOKE_PASSWORD; others: up.ps1 makes smoke@<name>.test)"
            throw 'Stopping: sign-in failed.'
        }
        $tokens[$name] = $login.Json.token
        $setCookies = @()
        if ($login.Headers.ContainsKey('Set-Cookie')) { $setCookies = $login.Headers['Set-Cookie'] }
        $refresh = $setCookies | Where-Object { $_ -like 'refreshToken=*' } | Select-Object -First 1
        if (-not $refresh) {
            Add-Result "${label}refresh cookie" $false 'no refreshToken Set-Cookie on sign-in'
        } else {
            $attributes = @($refresh.Split(';') | Select-Object -Skip 1 | ForEach-Object { $_.Trim() })
            $missing = @()
            foreach ($expected in 'Secure', 'HttpOnly', 'SameSite=None', 'Path=/api/auth/refresh') {
                if (-not ($attributes | Where-Object { $_ -ieq $expected })) { $missing += $expected }
            }
            $shown = ($attributes | Where-Object { $_ -notlike 'Expires=*' }) -join '; '
            Add-Result "${label}refresh cookie" ($missing.Count -eq 0) $(if ($missing.Count -eq 0) { $shown } else { "missing $($missing -join ', ') (has: $shown)" })
            # The browser sends the cookie only to its path: prove that path reaches the backend's refresh.
            $refreshed = Invoke-Api 'POST' '/api/auth/refresh' $null $null @{ Cookie = $refresh.Split(';')[0] }
            Add-Result "${label}refresh via /api/auth/refresh" ($refreshed.Status -eq 200 -and $refreshed.Json -and $refreshed.Json.token) (Describe $refreshed)
        }
        $echoed = $login.Headers.ContainsKey('X-Request-Id') -and $login.Headers['X-Request-Id'][0] -eq $requestId
        Add-Result "${label}X-Request-Id echoed" $echoed $(if ($echoed) { $requestId } else { 'not returned as sent' })

        $logLine = $null
        $deadline = (Get-Date).AddSeconds(10)
        while (-not $logLine -and (Get-Date) -lt $deadline) {
            # Into a variable first: Invoke-Docker returns one array, which a pipeline would pass on whole.
            $logLines = Invoke-Docker @('logs', '--since', $since, $backendContainer)
            $logLine = $logLines |
                Where-Object { $_ -like "*$requestId*" -and $_ -like '*request POST /auth/login*' } | Select-Object -First 1
            if (-not $logLine) { Start-Sleep -Milliseconds 500 }
        }
        if (-not $logLine) {
            Add-Result "${label}log line host" $false "no request line with $requestId in $backendContainer's log"
        } else {
            $hostMatch = [regex]::Match($logLine, 'host=(\S+)')
            $loggedHost = if ($hostMatch.Success) { $hostMatch.Groups[1].Value } else { '(none)' }
            Add-Result "${label}log line host" ($loggedHost -eq $expectedHost) "host=$loggedHost"
        }
    }

    # --- 4, at each customer: a sale on its kitchen stream, never on the others' ---
    $first = $true
    foreach ($name in $customers) {
        $script:base = Get-CustomerUrl $config $name
        $token = $tokens[$name]
        $label = if ($customers.Count -gt 1) { "${name}: " } else { '' }
        if ($customers.Count -gt 1) { Write-Step "A sale at $name" }

        $own = Open-KitchenStream $script:base $token
        if (-not $own) { Add-Result "${label}kitchen stream" $false $script:streamProblem; throw 'Stopping.' }
        $streams.Add($own)
        $watches = @{}
        foreach ($other in $customers | Where-Object { $_ -ne $name }) {
            $watch = Open-KitchenStream (Get-CustomerUrl $config $other) $tokens[$other]
            if (-not $watch) { Add-Result "${other}: kitchen stream" $false $script:streamProblem; throw 'Stopping.' }
            $streams.Add($watch)
            $watches[$other] = $watch
        }

        # A shift on this (unregistered) till: reuse an open one, or open one with a zero float.
        $open = Invoke-Api 'GET' '/api/shifts/open' $null $token
        $shiftId = $null
        if ($open.Status -eq 200 -and $open.Json -and $open.Json.PSObject.Properties.Name -contains 'shiftId' -and $open.Json.shiftId) { $shiftId = $open.Json.shiftId }
        if (-not $shiftId) {
            $opened = Invoke-Api 'POST' '/api/shifts/open' @{ openingFloat = 0 } $token
            if ($opened.Status -notin 200, 201) { Add-Result "${label}open shift" $false (Describe $opened); throw 'Stopping.' }
            $shiftId = $opened.Json.id
            $openedShifts[$name] = $shiftId
        }

        $menu = Invoke-Api 'GET' '/api/menu/today' $null $token
        $item = Select-KitchenItem $menu
        if (-not $item) {
            # No sellable kitchen item: add an always-on light meal (READY every day under All Day, no planning needed).
            $categories = Invoke-Api 'GET' '/api/admin/catalog/categories' $null $token
            $category = $null
            if ($categories.Status -eq 200 -and $categories.Json) {
                $category = Get-CategoryTree $categories.Json |
                    Where-Object { $_.group -eq 'LIGHT_MEALS' -and $_.assignable -and $_.active } | Select-Object -First 1
            }
            if (-not $category) { Add-Result "${label}menu item" $false "no sellable kitchen item, and no light-meals category to add one to ($(Describe $categories))"; throw 'Stopping.' }
            $added = Invoke-Api 'POST' '/api/admin/meal-catalog' @{
                name = 'Smoke check item'; description = 'Added by devstack/scripts/smoke.ps1'; price = 1.00; categoryId = $category.id
            } $token
            if ($added.Status -notin 200, 201) { Add-Result "${label}menu item" $false "could not add a light meal: $(Describe $added)"; throw 'Stopping.' }
            Write-Host "   ..  added the always-on light meal 'Smoke check item' to '$($category.name)' (reused next time)"
            $menu = Invoke-Api 'GET' '/api/menu/today' $null $token
            $item = Select-KitchenItem $menu
        }
        if (-not $item) { Add-Result "${label}menu item" $false "nothing sellable in the kitchen on today's menu ($(Describe $menu))"; throw 'Stopping.' }

        $orderBody = @{
            clientOrderId = [Guid]::NewGuid().ToString()
            lines = @(@{ dailyMealOptionId = $item.id; quantity = 1; extras = @() })
            payments = @(@{ method = 'CASH'; amount = $item.price; amountTendered = $item.price })
        }
        $placedAt = Get-Date
        $order = Invoke-Api 'POST' '/api/orders' $orderBody $token
        if ($order.Status -notin 200, 201 -or -not $order.Json) { Add-Result "${label}order" $false (Describe $order); throw 'Stopping.' }
        $orderId = [long]$order.Json.id
        $took = Wait-StreamEvent $own $orderId 5 $placedAt
        Add-Result "${label}stream event" ($took -ge 0) $(if ($took -ge 0) { "order $orderId ($($item.name)) arrived in {0:N2} s" -f $took } elseif ($took -eq -2) { 'the stream closed' } else { 'no event within 5 s (buffered?)' })
        foreach ($other in $watches.Keys) {
            # Nothing happens at the other customer meanwhile, so any event on its stream would be this order leaking.
            $leak = Wait-StreamEvent $watches[$other] 0 3 $placedAt
            Add-Result "${other}: no event from $name" ($leak -eq -1) $(if ($leak -eq -1) { "its kitchen stream received nothing within 3 s" } elseif ($leak -eq -2) { 'its stream closed' } else { 'received an event: a leak between customers' })
        }

        # Hold the stream open, then prove it still delivers: move the order on in the kitchen.
        $hold = if ($first) { $holdSeconds } else { 5 }
        Write-Host "   ..  holding the stream open for $hold s"
        $heldFrom = Get-Date
        $closedEarly = $false
        while (((Get-Date) - $heldFrom).TotalSeconds -lt $hold) {
            $left = [int][Math]::Max(1, $hold * 1000 - ((Get-Date) - $heldFrom).TotalMilliseconds)
            if (-not $own.Pending.Wait($left)) { break }
            if ($null -eq $own.Pending.Result) { $closedEarly = $true; break }
            $own.Pending = $own.Reader.ReadLineAsync()
        }
        $stillOpen = -not $closedEarly
        $statusFrom = Get-Date
        $patched = Invoke-Api 'PATCH' "/api/kitchen/orders/$orderId/status" @{ status = 'IN_PROGRESS' } $token
        $again = -1
        if ($stillOpen -and $patched.Status -eq 200) { $again = Wait-StreamEvent $own $orderId 5 $statusFrom }
        $held = [int]((Get-Date) - $heldFrom).TotalSeconds
        Add-Result "${label}stream after $hold s" ($stillOpen -and $again -ge 0) $(if (-not $stillOpen) { "closed after $held s" } elseif ($patched.Status -ne 200) { "status change refused: $(Describe $patched)" } elseif ($again -lt 0) { 'open, but the next event did not arrive' } else { "open for $held s and delivered the next event in {0:N2} s" -f $again })
        $null = Invoke-Api 'PATCH' "/api/kitchen/orders/$orderId/status" @{ status = 'DONE' } $token

        # Optional, first customer only: a quiet stream, held through the gateway with nothing but keepalives.
        if ($first -and $QuietHoldMinutes -gt 0) {
            $quietSeconds = $QuietHoldMinutes * 60
            Write-Host "   ..  holding a quiet kitchen stream for $QuietHoldMinutes min (keepalives only)"
            $quiet = Open-KitchenStream $script:base $token
            if (-not $quiet) { Add-Result "${label}quiet stream" $false $script:streamProblem; throw 'Stopping.' }
            try {
                $quietFrom = Get-Date
                $lastHeard = $quietFrom
                $keepalives = 0; $events = 0; $longestSilence = 0.0; $quietClosed = $false
                while (((Get-Date) - $quietFrom).TotalSeconds -lt $quietSeconds) {
                    $left = [int][Math]::Max(1, $quietSeconds * 1000 - ((Get-Date) - $quietFrom).TotalMilliseconds)
                    if (-not $quiet.Pending.Wait($left)) { break }
                    $line = $quiet.Pending.Result
                    if ($null -eq $line) { $quietClosed = $true; break }
                    if ($line -eq ':keepalive') {
                        $silence = ((Get-Date) - $lastHeard).TotalSeconds
                        if ($silence -gt $longestSilence) { $longestSilence = $silence }
                        $lastHeard = Get-Date
                        $keepalives++
                    } elseif ($line.StartsWith('data:')) { $events++ }
                    $quiet.Pending = $quiet.Reader.ReadLineAsync()
                }
                $silence = ((Get-Date) - $lastHeard).TotalSeconds
                if ($silence -gt $longestSilence) { $longestSilence = $silence }
                $heldQuiet = [int]((Get-Date) - $quietFrom).TotalSeconds
                $quietOk = -not $quietClosed -and $keepalives -gt 0 -and $longestSilence -lt 60
                $detail = "open {0} s, {1} keepalives, longest silence {2:N0} s, {3} events" -f $heldQuiet, $keepalives, $longestSilence, $events
                if ($quietClosed) { $detail = "closed after $heldQuiet s; " + $detail }
                Add-Result "${label}quiet stream $QuietHoldMinutes min" $quietOk $detail
            } finally {
                Close-KitchenStream $quiet
            }
        }

        foreach ($s in @($streams)) { Close-KitchenStream $s }
        $streams.Clear()
        $first = $false
    }

    # --- multi mode: an address no customer has ---
    if ($multi -and -not $Customer) {
        Write-Step 'An unknown address'
        $script:base = Get-CustomerUrl $config $customers[0]
        foreach ($path in '/api/health', '/api/public/board/today') {
            $unknown = Invoke-Api 'GET' $path $null $null $null 'nobody.pos.localhost'
            Add-Result "nobody.pos.localhost$path" ($unknown.Status -eq 404 -and [string]::IsNullOrEmpty($unknown.Text)) "HTTP $($unknown.Status), body $(if ([string]::IsNullOrEmpty($unknown.Text)) { 'empty' } else { "$($unknown.Text.Length) characters" })"
        }
    }
} catch {
    if ($results.Count -eq 0 -or @($results | Where-Object { -not $_.Passed }).Count -eq 0) {
        Add-Result 'smoke check' $false $_.Exception.Message
    }
} finally {
    foreach ($s in @($streams)) { Close-KitchenStream $s }
    foreach ($name in @($openedShifts.Keys)) {
        # Close the shifts this check opened: counted cash is what the till expects (the float was zero).
        $script:base = Get-CustomerUrl $config $name
        $shiftToken = $tokens[$name]
        $summary = Invoke-Api 'GET' "/api/shifts/$($openedShifts[$name])/summary" $null $shiftToken
        $counted = 0
        if ($summary.Json -and $summary.Json.PSObject.Properties.Name -contains 'expectedCash') { $counted = $summary.Json.expectedCash }
        $closed = Invoke-Api 'POST' "/api/shifts/$($openedShifts[$name])/close" @{ countedCash = $counted } $shiftToken
        if ($closed.Status -notin 200, 201, 204) { Write-Problem "The shift this check opened at $name ($($openedShifts[$name])) is still open: $(Describe $closed). reset.ps1 clears it." }
    }
    $streamClient.Dispose()
    $client.Dispose()
    $tokens.Clear()
}

Write-Host ''
$failed = @($results | Where-Object { -not $_.Passed })
if ($failed.Count -eq 0) { Write-Host "Smoke check passed ($($results.Count) checks)." -ForegroundColor Green; exit 0 }
Write-Host "Smoke check FAILED: $($failed.Count) of $($results.Count) checks." -ForegroundColor Red
exit 1
