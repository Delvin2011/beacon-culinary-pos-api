# Login protection: monitor query pack

Read-only T-SQL for the WS1-1 monitoring period (mode `monitor`). Every query only reads `login_attempts` and `user_auth_state`. Run them against the Azure dev database with a read-only login where possible.

All `attempted_at` values are UTC. To show South African time, add two hours: `DATEADD(HOUR, 2, attempted_at)`. Africa/Johannesburg has no daylight saving.

Change `@since` to the period you want to look at.

## 1. Failures per hour by method

```sql
DECLARE @since DATETIME2 = DATEADD(DAY, -7, SYSUTCDATETIME());

SELECT DATEADD(HOUR, DATEDIFF(HOUR, 0, [attempted_at]), 0) AS [hour_utc],
       [method],
       SUM(CASE WHEN [outcome] IN ('BAD_CREDENTIAL', 'UNKNOWN_USER', 'DISABLED') THEN 1 ELSE 0 END) AS [failures],
       SUM(CASE WHEN [outcome] = 'SUCCESS' THEN 1 ELSE 0 END) AS [successes],
       SUM(CASE WHEN [outcome] = 'VALIDATION' THEN 1 ELSE 0 END) AS [validation_errors]
FROM [login_attempts]
WHERE [attempted_at] >= @since
GROUP BY DATEADD(HOUR, DATEDIFF(HOUR, 0, [attempted_at]), 0), [method]
ORDER BY [hour_utc] DESC, [method];
```

## 2. Would-block events by rule

How often each rule would have blocked, and how many distinct accounts or callers that affected. This is the main input for deciding whether the D3 thresholds are right before enforce mode.

```sql
DECLARE @since DATETIME2 = DATEADD(DAY, -7, SYSUTCDATETIME());

SELECT [rule_triggered],
       [method],
       COUNT(*)                     AS [would_block_attempts],
       COUNT(DISTINCT [identifier]) AS [distinct_identifiers],
       COUNT(DISTINCT [client_ip])  AS [distinct_client_ips],
       SUM(CASE WHEN [outcome] = 'SUCCESS' THEN 1 ELSE 0 END) AS [would_have_blocked_a_success],
       MIN([attempted_at])          AS [first_utc],
       MAX([attempted_at])          AS [last_utc]
FROM [login_attempts]
WHERE [attempted_at] >= @since AND [would_block] = 1
GROUP BY [rule_triggered], [method]
ORDER BY [would_block_attempts] DESC;
```

`would_have_blocked_a_success` counts genuine users who got their credential right while a rule would have blocked them. Those are the false positives to look at before enforcing.

Alert-only events (`MANAGER_PIN_GLOBAL`) have `would_block = 0`:

```sql
SELECT [rule_triggered], COUNT(*) AS [attempts], MIN([attempted_at]) AS [first_utc], MAX([attempted_at]) AS [last_utc]
FROM [login_attempts]
WHERE [rule_triggered] IS NOT NULL AND [would_block] = 0
GROUP BY [rule_triggered];
```

## 3. Network values (identify the Azure ingress)

Shows what the app actually receives. If every row has the same `remote_addr` and a populated `forwarded_for`, the app sits behind a proxy. That address (or its range) is the candidate for `security.login-protection.trusted-proxies`.

```sql
DECLARE @since DATETIME2 = DATEADD(DAY, -7, SYSUTCDATETIME());

SELECT [remote_addr],
       CASE WHEN [forwarded_for] IS NULL THEN 'none' ELSE 'present' END AS [xff],
       COUNT(*) AS [attempts],
       MIN([attempted_at]) AS [first_utc],
       MAX([attempted_at]) AS [last_utc]
FROM [login_attempts]
WHERE [attempted_at] >= @since
GROUP BY [remote_addr], CASE WHEN [forwarded_for] IS NULL THEN 'none' ELSE 'present' END
ORDER BY [attempts] DESC;

SELECT TOP 50 [forwarded_for], COUNT(*) AS [attempts]
FROM [login_attempts]
WHERE [attempted_at] >= @since AND [forwarded_for] IS NOT NULL
GROUP BY [forwarded_for]
ORDER BY [attempts] DESC;

SELECT TOP 20 [user_agent], COUNT(*) AS [attempts]
FROM [login_attempts]
WHERE [attempted_at] >= @since
GROUP BY [user_agent]
ORDER BY [attempts] DESC;
```

## 4. Top identifiers by failures

`identifier` is the lower-cased email (PASSWORD), the cashierId (PIN), or the calling user's id (MANAGER_PIN, CHANGE_PASSWORD). Emails are personal data: share results only with people who need them.

```sql
DECLARE @since DATETIME2 = DATEADD(DAY, -7, SYSUTCDATETIME());

SELECT TOP 25 a.[method],
       a.[identifier],
       u.[name],
       u.[role],
       SUM(CASE WHEN a.[outcome] IN ('BAD_CREDENTIAL', 'UNKNOWN_USER', 'DISABLED') THEN 1 ELSE 0 END) AS [failures],
       SUM(CASE WHEN a.[outcome] = 'UNKNOWN_USER' THEN 1 ELSE 0 END) AS [unknown_user],
       SUM(CASE WHEN a.[outcome] = 'SUCCESS' THEN 1 ELSE 0 END) AS [successes],
       COUNT(DISTINCT a.[client_ip]) AS [distinct_ips]
FROM [login_attempts] a
LEFT JOIN [users] u ON u.[id] = a.[user_id]
WHERE a.[attempted_at] >= @since
GROUP BY a.[method], a.[identifier], u.[name], u.[role]
HAVING SUM(CASE WHEN a.[outcome] IN ('BAD_CREDENTIAL', 'UNKNOWN_USER', 'DISABLED') THEN 1 ELSE 0 END) > 0
ORDER BY [failures] DESC;
```

## 5. PIN length evidence (decision D4)

How many successful PIN logins used a PIN that is not 6 digits. Phase 2 will require exactly 6 digits; these users would need a new PIN first.

```sql
DECLARE @since DATETIME2 = DATEADD(DAY, -30, SYSUTCDATETIME());

SELECT SUM(CASE WHEN [pin_non_standard] = 1 THEN 1 ELSE 0 END) AS [non_standard_successes],
       SUM(CASE WHEN [pin_non_standard] = 0 THEN 1 ELSE 0 END) AS [six_digit_successes]
FROM [login_attempts]
WHERE [method] = 'PIN' AND [outcome] = 'SUCCESS' AND [attempted_at] >= @since;

SELECT u.[id], u.[name], u.[role], MAX(a.[attempted_at]) AS [last_non_standard_login_utc]
FROM [login_attempts] a
JOIN [users] u ON u.[id] = a.[user_id]
WHERE a.[method] = 'PIN' AND a.[outcome] = 'SUCCESS' AND a.[pin_non_standard] = 1
GROUP BY u.[id], u.[name], u.[role]
ORDER BY [last_non_standard_login_utc] DESC;
```

## 6. Health checks

```sql
-- Rows per day and the oldest row (retention keeps 90 days).
SELECT CAST([attempted_at] AS DATE) AS [day_utc], COUNT(*) AS [attempts]
FROM [login_attempts]
GROUP BY CAST([attempted_at] AS DATE)
ORDER BY [day_utc] DESC;

SELECT MIN([attempted_at]) AS [oldest_utc], COUNT(*) AS [total_rows] FROM [login_attempts];

-- Lock state. In monitor mode only last_success_at is ever written; lock columns stay empty.
SELECT [scope], COUNT(*) AS [rows], SUM(CASE WHEN [locked_until] IS NOT NULL THEN 1 ELSE 0 END) AS [with_lock]
FROM [user_auth_state]
GROUP BY [scope];
```
