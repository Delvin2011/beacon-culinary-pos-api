<#
.SYNOPSIS
    T1-L0: the end-to-end check through the gateway, as a browser would call it.

.DESCRIPTION
    Against https://<customer>.pos.localhost (the active customer; beaconculinary by default):
      1. /api/health is UP through the proxy, with an X-Request-Id header.
      2. Password sign-in (SMOKE_EMAIL / SMOKE_PASSWORD from stack.env, an ADMIN) sets the refresh cookie Secure,
         HttpOnly, SameSite=None, Path=/api/auth/refresh, and /api/auth/refresh with that cookie works.
      3. Our own X-Request-Id comes back, and the backend's log line for it shows host=<customer>.pos.localhost.
      4. The kitchen stream, opened through the proxy, delivers the event of an order placed through the API within
         5 s, stays open for 2 minutes (-Quick: 5 s), and still delivers the next event after that.
    The order: one kitchen item from today's menu (READY, kitchen station), paid in cash, on the till's open shift
    (opened with a zero float and closed again when the check opened it). When the menu has none (the demo data's set
    meals are only planned, and it has no light meals), the check adds an always-on light meal, "Smoke check item",
    through the admin API, as an admin would; later runs reuse it. The sale and the item stay in the database until
    reset.ps1.
    Tokens, cookies and passwords are never printed. Exits 0 when every check passes, 1 otherwise.

.PARAMETER Quick
    Hold the stream for 5 seconds instead of 2 minutes.
#>
[CmdletBinding()]
param(
    [string] $Customer,
    [switch] $Quick
)
. (Join-Path $PSScriptRoot '_common.ps1')
Add-Type -AssemblyName System.Net.Http

$config = Get-StackConfig -Customer $Customer
Assert-Docker
if (-not $config.SmokeEmail -or -not $config.SmokePassword) { throw 'Set SMOKE_EMAIL and SMOKE_PASSWORD (an ADMIN account) in devstack/stack.env.' }
$base = Get-CustomerUrl $config $config.ActiveCustomer
$expectedHost = "$($config.ActiveCustomer).pos.localhost$($config.PortSuffix)"
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

<# One request. Returns status, response headers (name -> values), body text and parsed JSON (when JSON). #>
function Invoke-Api([string] $Method, [string] $Path, $Body, [string] $Token, [hashtable] $Headers) {
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), "$base$Path")
    if ($Token) { $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token) }
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

Write-Step "Smoke check against $base"
$stream = $null
$reader = $null
$openedShiftId = $null
$token = $null
try {
    # 1. Health through the proxy.
    try {
        $health = Invoke-Api 'GET' '/api/health'
        $up = $health.Status -eq 200 -and $health.Json -and $health.Json.status -eq 'UP'
        Add-Result 'health' ($up -and $health.Headers.ContainsKey('X-Request-Id')) ("$(Describe $health), X-Request-Id " + $(if ($health.Headers.ContainsKey('X-Request-Id')) { 'present' } else { 'missing' }))
    } catch {
        Add-Result 'health' $false "no answer: $($_.Exception.GetBaseException().Message) (status.ps1 checks the hosts file and certificate)"
        throw 'Stopping: the gateway is not reachable.'
    }

    # 2 and 3. Sign in with our own request id; check the cookie and the log line.
    $requestId = 'smoke-' + [Guid]::NewGuid().ToString('N').Substring(0, 20)
    $since = (Get-Date).ToUniversalTime().AddSeconds(-5).ToString('yyyy-MM-ddTHH:mm:ssZ')
    $login = Invoke-Api 'POST' '/api/auth/login' @{ email = $config.SmokeEmail; password = $config.SmokePassword } $null @{ 'X-Request-Id' = $requestId }
    if ($login.Status -ne 200 -or -not $login.Json -or -not $login.Json.token) {
        Add-Result 'sign-in' $false "$(Describe $login) (SMOKE_EMAIL and SMOKE_PASSWORD in stack.env)"
        throw 'Stopping: sign-in failed.'
    }
    $token = $login.Json.token
    $setCookies = @()
    if ($login.Headers.ContainsKey('Set-Cookie')) { $setCookies = $login.Headers['Set-Cookie'] }
    $refresh = $setCookies | Where-Object { $_ -like 'refreshToken=*' } | Select-Object -First 1
    if (-not $refresh) {
        Add-Result 'refresh cookie' $false 'no refreshToken Set-Cookie on sign-in'
    } else {
        $attributes = @($refresh.Split(';') | Select-Object -Skip 1 | ForEach-Object { $_.Trim() })
        $missing = @()
        foreach ($expected in 'Secure', 'HttpOnly', 'SameSite=None', 'Path=/api/auth/refresh') {
            if (-not ($attributes | Where-Object { $_ -ieq $expected })) { $missing += $expected }
        }
        $shown = ($attributes | Where-Object { $_ -notlike 'Expires=*' }) -join '; '
        Add-Result 'refresh cookie' ($missing.Count -eq 0) $(if ($missing.Count -eq 0) { $shown } else { "missing $($missing -join ', ') (has: $shown)" })
        # The browser sends the cookie only to its path: prove that path reaches the backend's refresh.
        $cookieValue = $refresh.Split(';')[0]
        $refreshed = Invoke-Api 'POST' '/api/auth/refresh' $null $null @{ Cookie = $cookieValue }
        Add-Result 'refresh via /api/auth/refresh' ($refreshed.Status -eq 200 -and $refreshed.Json -and $refreshed.Json.token) (Describe $refreshed)
    }
    $echoed = $login.Headers.ContainsKey('X-Request-Id') -and $login.Headers['X-Request-Id'][0] -eq $requestId
    Add-Result 'X-Request-Id echoed' $echoed $(if ($echoed) { $requestId } else { 'not returned as sent' })

    $logLine = $null
    $deadline = (Get-Date).AddSeconds(10)
    while (-not $logLine -and (Get-Date) -lt $deadline) {
        $logLine = Invoke-Docker @('logs', '--since', $since, $backendContainer) |
            Where-Object { $_ -like "*$requestId*" -and $_ -like '*request POST /auth/login*' } | Select-Object -First 1
        if (-not $logLine) { Start-Sleep -Milliseconds 500 }
    }
    if (-not $logLine) {
        Add-Result 'log line host' $false "no request line with $requestId in $backendContainer's log"
    } else {
        $hostMatch = [regex]::Match($logLine, 'host=(\S+)')
        $loggedHost = if ($hostMatch.Success) { $hostMatch.Groups[1].Value } else { '(none)' }
        Add-Result 'log line host' ($loggedHost -eq $expectedHost) "host=$loggedHost"
    }

    # 4. The kitchen stream through the proxy.
    $streamRequest = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Get, "$base/api/kitchen/orders/stream")
    $streamRequest.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $token)
    $streamRequest.Headers.Accept.ParseAdd('text/event-stream')
    $streamTask = $streamClient.SendAsync($streamRequest, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead)
    if (-not $streamTask.Wait(10000)) { Add-Result 'kitchen stream' $false 'no response headers within 10 s (buffered?)'; throw 'Stopping.' }
    $streamResponse = $streamTask.Result
    if ([int]$streamResponse.StatusCode -ne 200) { Add-Result 'kitchen stream' $false "HTTP $([int]$streamResponse.StatusCode)"; throw 'Stopping.' }
    $stream = $streamResponse.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
    $reader = [System.IO.StreamReader]::new($stream)
    $pending = $reader.ReadLineAsync()

    <# Waits up to $Seconds for a data line that mentions this order; returns the seconds it took, or -1. #>
    function Wait-OrderEvent([long] $OrderId, [double] $Seconds, [datetime] $From) {
        $until = $From.AddSeconds($Seconds)
        while ($true) {
            $left = [int][Math]::Max(0, ($until - (Get-Date)).TotalMilliseconds)
            if (-not $script:pending.Wait($left)) { return -1 }
            $line = $script:pending.Result
            if ($null -eq $line) { return -2 }
            $script:pending = $script:reader.ReadLineAsync()
            # The kitchen event names the order "orderId" (inside "order"); "id" is accepted too.
            if ($line.StartsWith('data:') -and $line -match "`"(orderId|id)`"\s*:\s*$OrderId\b") { return ((Get-Date) - $From).TotalSeconds }
        }
    }

    # A shift on this (unregistered) till: reuse an open one, or open one with a zero float.
    $open = Invoke-Api 'GET' '/api/shifts/open' $null $token
    $shiftId = $null
    if ($open.Status -eq 200 -and $open.Json -and $open.Json.PSObject.Properties.Name -contains 'shiftId' -and $open.Json.shiftId) { $shiftId = $open.Json.shiftId }
    if (-not $shiftId) {
        $opened = Invoke-Api 'POST' '/api/shifts/open' @{ openingFloat = 0 } $token
        if ($opened.Status -notin 200, 201) { Add-Result 'open shift' $false (Describe $opened); throw 'Stopping.' }
        $shiftId = $opened.Json.id
        $openedShiftId = $shiftId
    }

    <# A kitchen item on today's menu that the till can sell. Light-meal stock only warns, never blocks a sale, so
       unitsAvailable is not a condition; beverages go to the bar, not the kitchen stream. #>
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
        if (-not $category) { Add-Result 'menu item' $false "no sellable kitchen item, and no light-meals category to add one to ($(Describe $categories))"; throw 'Stopping.' }
        $added = Invoke-Api 'POST' '/api/admin/meal-catalog' @{
            name = 'Smoke check item'; description = 'Added by devstack/scripts/smoke.ps1'; price = 1.00; categoryId = $category.id
        } $token
        if ($added.Status -notin 200, 201) { Add-Result 'menu item' $false "could not add a light meal: $(Describe $added)"; throw 'Stopping.' }
        Write-Host "   ..  added the always-on light meal 'Smoke check item' to '$($category.name)' (reused next time)"
        $menu = Invoke-Api 'GET' '/api/menu/today' $null $token
        $item = Select-KitchenItem $menu
    }
    if (-not $item) { Add-Result 'menu item' $false "nothing sellable in the kitchen on today's menu ($(Describe $menu))"; throw 'Stopping.' }

    $orderBody = @{
        clientOrderId = [Guid]::NewGuid().ToString()
        lines = @(@{ dailyMealOptionId = $item.id; quantity = 1; extras = @() })
        payments = @(@{ method = 'CASH'; amount = $item.price; amountTendered = $item.price })
    }
    $placedAt = Get-Date
    $order = Invoke-Api 'POST' '/api/orders' $orderBody $token
    if ($order.Status -notin 200, 201 -or -not $order.Json) { Add-Result 'order' $false (Describe $order); throw 'Stopping.' }
    $orderId = [long]$order.Json.id
    $took = Wait-OrderEvent $orderId 5 $placedAt
    Add-Result 'stream event' ($took -ge 0) $(if ($took -ge 0) { "order $orderId ($($item.name)) arrived in {0:N2} s" -f $took } elseif ($took -eq -2) { 'the stream closed' } else { 'no event within 5 s (buffered?)' })

    # Hold the stream open, then prove it still delivers: move the order on in the kitchen.
    Write-Host "   ..  holding the stream open for $holdSeconds s"
    $heldFrom = Get-Date
    # Read whatever arrives (the blank line that ends each event, comments) until the time is up; only the end of
    # the stream (a null line) counts as closed.
    $closedEarly = $false
    while (((Get-Date) - $heldFrom).TotalSeconds -lt $holdSeconds) {
        $left = [int][Math]::Max(1, $holdSeconds * 1000 - ((Get-Date) - $heldFrom).TotalMilliseconds)
        if (-not $pending.Wait($left)) { break }
        if ($null -eq $pending.Result) { $closedEarly = $true; break }
        $pending = $reader.ReadLineAsync()
    }
    $stillOpen = -not $closedEarly
    $statusFrom = Get-Date
    $patched = Invoke-Api 'PATCH' "/api/kitchen/orders/$orderId/status" @{ status = 'IN_PROGRESS' } $token
    $again = -1
    if ($stillOpen -and $patched.Status -eq 200) { $again = Wait-OrderEvent $orderId 5 $statusFrom }
    $held = [int]((Get-Date) - $heldFrom).TotalSeconds
    Add-Result "stream after $holdSeconds s" ($stillOpen -and $again -ge 0) $(if (-not $stillOpen) { "closed after $held s" } elseif ($patched.Status -ne 200) { "status change refused: $(Describe $patched)" } elseif ($again -lt 0) { 'open, but the next event did not arrive' } else { "open for $held s and delivered the next event in {0:N2} s" -f $again })
    $null = Invoke-Api 'PATCH' "/api/kitchen/orders/$orderId/status" @{ status = 'DONE' } $token
} catch {
    if ($results.Count -eq 0 -or @($results | Where-Object { -not $_.Passed }).Count -eq 0) {
        Add-Result 'smoke check' $false $_.Exception.Message
    }
} finally {
    if ($reader) { $reader.Dispose() }
    if ($stream) { $stream.Dispose() }
    if ($openedShiftId -and $token) {
        # Close the shift this check opened: counted cash is what the till expects (the float was zero).
        $summary = Invoke-Api 'GET' "/api/shifts/$openedShiftId/summary" $null $token
        $counted = 0
        if ($summary.Json -and $summary.Json.PSObject.Properties.Name -contains 'expectedCash') { $counted = $summary.Json.expectedCash }
        $closed = Invoke-Api 'POST' "/api/shifts/$openedShiftId/close" @{ countedCash = $counted } $token
        if ($closed.Status -notin 200, 201, 204) { Write-Problem "The shift this check opened ($openedShiftId) is still open: $(Describe $closed). reset.ps1 clears it." }
    }
    $streamClient.Dispose()
    $client.Dispose()
    $token = $null
}

Write-Host ''
$failed = @($results | Where-Object { -not $_.Passed })
if ($failed.Count -eq 0) { Write-Host "Smoke check passed ($($results.Count) checks)." -ForegroundColor Green; exit 0 }
Write-Host "Smoke check FAILED: $($failed.Count) of $($results.Count) checks." -ForegroundColor Red
exit 1
