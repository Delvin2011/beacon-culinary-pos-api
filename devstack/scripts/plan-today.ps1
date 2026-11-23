<#
.SYNOPSIS
    T1-L0: makes today's demo meals sellable in the active customer's database, so a developer can ring up a sale by
    hand (docs/2026-11-23-local-stack.md, "Selling by hand").

.DESCRIPTION
    Through the API, as an admin (SMOKE_EMAIL / SMOKE_PASSWORD in stack.env), the way the kitchen would:
      1. Today's demo plan, as the demo data seeds it on the day the migrations run:
           Breakfast  Rice & Beef, Chicken & Potatoes
           Lunch      Rice & Chicken, Potatoes & Beef; extras Rice, Chicken, Potatoes, Beef
         Each item missing for today is planned (-Portions each).
      2. PLANNED items: the period's ingredient requirements are confirmed (this also creates the stock request a
         real kitchen gets).
      3. Reviewed items: the actual portions are recorded, which makes them READY, on sale.
      4. An always-on light meal, "Smoke check item" (the one smoke.ps1 uses), so there is something to sell at any
         hour: set meals are on the till only during their period (Breakfast 07:00-10:00, Lunch 12:00-14:30).
    Idempotent: READY items are left alone; a second run changes nothing. A meal the database doesn't have (a new
    customer's) is skipped and named.

    It refuses a database that isn't the stack's: the customer must be one of the stack's, its database must exist on
    the stack's SQL Server, and the backend must be serving it. It only calls https://<customer>.pos.localhost.

.PARAMETER Portions
    Portions (and extras) per item when planning (default 20).
#>
[CmdletBinding()]
param(
    [string] $Customer,
    [ValidateRange(1, 500)] [int] $Portions = 20
)
. (Join-Path $PSScriptRoot '_common.ps1')

$config = Get-StackConfig -Customer $Customer
Assert-Docker
Assert-StackNetwork $config
$name = $config.ActiveCustomer

# --- the stack's database only ---
if ((Get-ManagedCustomers) -notcontains $name) { throw "$name isn't one of the stack's customers ($((Get-ManagedCustomers) -join ', '))." }
if (-not (Test-StackDatabase $config $name)) { throw "There is no database $name on the stack's SQL Server." }
$backendUrl = (Invoke-Docker @('inspect', '--format', '{{range .Config.Env}}{{println .}}{{end}}', "$($config.StackName)-backend-1")) |
    Where-Object { $_ -like 'SPRING_DATASOURCE_URL=*' } | Select-Object -First 1
if ($backendUrl -notmatch "databaseName=$name;") {
    throw "The backend isn't serving $name. Run devstack/scripts/up.ps1 -Customer $name first."
}
if (-not $config.SmokeEmail -or -not $config.SmokePassword) { throw 'Set SMOKE_EMAIL and SMOKE_PASSWORD (an ADMIN of this customer) in devstack/stack.env.' }

$base = "$(Get-CustomerUrl $config $name)/api"
$headers = @{}

function Invoke-Admin([string] $Method, [string] $Path, $Body) {
    $call = @{ Method = $Method; Uri = "$base$Path"; Headers = $headers; ContentType = 'application/json'; UseBasicParsing = $true }
    if ($null -ne $Body) { $call.Body = ($Body | ConvertTo-Json -Depth 5 -Compress) }
    try {
        $result = Invoke-RestMethod @call
    } catch {
        $status = $null
        if ($_.Exception.PSObject.Properties.Name -contains 'Response' -and $_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        $detail = if ($_.ErrorDetails) { $_.ErrorDetails.Message } else { $_.Exception.Message }
        throw "$Method $Path failed (HTTP $status): $detail"
    }
    # Windows PowerShell 5.1 returns a JSON array as one object: send its items on one by one, so @(...) is the list.
    if ($result -is [array]) { foreach ($item in $result) { $item } } else { $result }
}

Write-Step "Today's demo meals in $name"
$login = Invoke-Admin 'POST' '/auth/login' @{ email = $config.SmokeEmail; password = $config.SmokePassword }
$headers.Authorization = "Bearer $($login.token)"
$login = $null

# The business date (the site's time zone, Africa/Johannesburg by default), as the backend counts "today".
$today = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTime]::UtcNow, 'South Africa Standard Time').ToString('yyyy-MM-dd')

$periods = @(Invoke-Admin 'GET' '/meal-periods' $null)
$meals = @(Invoke-Admin 'GET' '/admin/meal-catalog' $null)
$components = @(Invoke-Admin 'GET' '/admin/component-catalog' $null)

$demoPlan = [ordered]@{
    'Breakfast' = @{ Meals = @('Rice & Beef', 'Chicken & Potatoes'); Extras = @() }
    'Lunch'     = @{ Meals = @('Rice & Chicken', 'Potatoes & Beef'); Extras = @('Rice', 'Chicken', 'Potatoes', 'Beef') }
}
$ready = @()
$skipped = @()

foreach ($periodName in $demoPlan.Keys) {
    # /meal-periods lists the active periods only.
    $period = $periods | Where-Object { $_.name -eq $periodName } | Select-Object -First 1
    if (-not $period) { $skipped += "$periodName (no active period)"; continue }
    $wanted = $demoPlan[$periodName]
    $plan = Invoke-Admin 'GET' "/admin/daily-planning/$today`?period=$([Uri]::EscapeDataString($periodName))" $null

    # 1. Plan what is missing today.
    $added = $false
    foreach ($mealName in $wanted.Meals) {
        if (@($plan.options) | Where-Object { $_.name -eq $mealName }) { continue }
        $meal = $meals | Where-Object { $_.name -eq $mealName -and $_.active } | Select-Object -First 1
        if (-not $meal) { $skipped += "$mealName (not in the catalogue)"; continue }
        $null = Invoke-Admin 'POST' '/admin/daily-options' @{ mealPeriodId = $period.id; optionDate = $today; mealCatalogId = $meal.id; plannedPortions = $Portions }
        $added = $true
    }
    foreach ($componentName in $wanted.Extras) {
        if (@($plan.componentStock) | Where-Object { $_.componentName -eq $componentName }) { continue }
        $component = $components | Where-Object { $_.name -eq $componentName -and $_.active } | Select-Object -First 1
        if (-not $component) { $skipped += "$componentName extra (not in the catalogue)"; continue }
        $null = Invoke-Admin 'POST' '/admin/daily-component-stock' @{ componentCatalogId = $component.id; mealPeriodId = $period.id; optionDate = $today; bufferQuantity = $Portions }
        $added = $true
    }
    if ($added) { $plan = Invoke-Admin 'GET' "/admin/daily-planning/$today`?period=$([Uri]::EscapeDataString($periodName))" $null }

    # 2. Confirm the ingredient requirements of whatever is still PLANNED.
    $items = @(@($plan.options) | Where-Object { $_ }) + @(@($plan.componentStock) | Where-Object { $_ })
    if ($items | Where-Object { $_.status -eq 'PLANNED' }) {
        $null = Invoke-Admin 'POST' "/admin/daily-planning/$today/confirm-ingredient-requirements" @{ period = $periodName; adjustments = @() }
        $plan = Invoke-Admin 'GET' "/admin/daily-planning/$today`?period=$([Uri]::EscapeDataString($periodName))" $null
    }

    # 3. Record the actual portions: READY, on sale.
    foreach ($option in @($plan.options) | Where-Object { $_ -and ($wanted.Meals -contains $_.name) }) {
        if ($option.status -eq 'INGREDIENTS_REVIEWED') {
            $actual = if ($option.plannedPortions) { $option.plannedPortions } else { $Portions }
            $null = Invoke-Admin 'PUT' "/admin/daily-options/$($option.id)/actual" @{ actualPortions = $actual }
            $option.status = 'READY'
        }
        if ($option.status -eq 'READY') { $ready += "$periodName  $($option.name)" }
    }
    foreach ($stock in @($plan.componentStock) | Where-Object { $_ -and ($wanted.Extras -contains $_.componentName) }) {
        if ($stock.status -eq 'INGREDIENTS_REVIEWED') {
            $actual = if ($stock.bufferQuantity) { $stock.bufferQuantity } else { $Portions }
            $null = Invoke-Admin 'PUT' "/admin/daily-component-stock/$($stock.id)/actual" @{ actualQuantity = $actual }
            $stock.status = 'READY'
        }
        if ($stock.status -eq 'READY') { $ready += "$periodName  extra: $($stock.componentName)" }
    }
}

# 4. An always-on light meal, on sale at any hour (the same one smoke.ps1 adds).
$lightMeal = $meals | Where-Object { $_.name -eq 'Smoke check item' -and $_.active } | Select-Object -First 1
if (-not $lightMeal) {
    $tree = @(Invoke-Admin 'GET' '/admin/catalog/categories' $null)
    $flat = [System.Collections.Generic.List[object]]::new()
    $queue = [System.Collections.Generic.Queue[object]]::new()
    foreach ($node in $tree) { $queue.Enqueue($node) }
    while ($queue.Count -gt 0) {
        $node = $queue.Dequeue()
        $flat.Add($node)
        if ($node.PSObject.Properties.Name -contains 'children') { foreach ($child in @($node.children)) { if ($child) { $queue.Enqueue($child) } } }
    }
    $category = $flat | Where-Object { $_.group -eq 'LIGHT_MEALS' -and $_.assignable -and $_.active } | Select-Object -First 1
    if ($category) {
        $null = Invoke-Admin 'POST' '/admin/meal-catalog' @{ name = 'Smoke check item'; description = 'Added by devstack/scripts/smoke.ps1'; price = 1.00; categoryId = $category.id }
        $ready += "All Day  Smoke check item (added to '$($category.name)')"
    } else {
        $skipped += 'Smoke check item (no active light-meals category)'
    }
} else {
    $ready += 'All Day  Smoke check item'
}

$headers.Authorization = $null
foreach ($line in $ready) { Write-Ok $line }
foreach ($line in $skipped) { Write-Problem "skipped: $line" }

$now = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTime]::UtcNow, 'South Africa Standard Time').TimeOfDay
$open = @($periods | Where-Object { ($_.allDay -or ($now -ge [TimeSpan]::Parse($_.startTime) -and $now -le [TimeSpan]::Parse($_.endTime))) } | ForEach-Object { $_.name })
Write-Host ''
Write-Host "Ready for $today. Open now: $($open -join ', ')." -ForegroundColor Green
Write-Host '  Set meals are on the till only during their period; the light meal sells at any hour.'
