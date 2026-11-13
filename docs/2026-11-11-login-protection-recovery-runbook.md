# Login protection: recovery runbook

For the system owner. Use it when nobody who can unlock sign-in can sign in: for example every admin is locked at level 3 ("Ask a manager to unlock sign-in"), or a mistake in the thresholds locks everyone out.

**Normal case first:** if **another** admin can still sign in, they use the staff page, or `POST /admin/staff/{id}/unlock`. That is audited, and no database access is needed.

**An admin can't unlock themselves.** Unlocking your own row is refused (409 `SELF_UNLOCK`), and setting your own PIN or password doesn't clear your lock. So this runbook is also needed when:
- the **only** active admin reaches level 3; or
- every admin who could help is locked or unavailable.

A level-1 or level-2 lock (15 or 60 minutes) can simply be waited out.

## Option A: switch enforcement off (no SQL, fastest)

1. In the Azure app settings, set `LOGIN_PROTECTION_MODE=monitor`.
2. Restart the app.
3. Nobody is refused any more. Attempts are still recorded and flagged, so monitoring keeps working.
4. Sign in as an admin and unlock the affected staff from the staff page.
5. Set `LOGIN_PROTECTION_MODE=enforce` again and restart.

Locks stay in the database while in monitor mode; they're just not applied. Unlocking from the staff page in step 4 clears them before enforcement returns.

## Option B: unlock admins in the database

Use this only when Option A isn't possible. Run it against the **production database**, with a login that may update `user_auth_state`.

**1. See who is locked (read-only):**

```sql
SELECT u.[id], u.[name], u.[role], s.[scope], s.[lock_level], s.[locked_until], s.[locked_at], s.[lock_reason]
FROM [user_auth_state] s
JOIN [users] u ON u.[id] = s.[user_id]
WHERE s.[locked_until] > SYSUTCDATETIME()
ORDER BY u.[role], u.[name], s.[scope];
```

`locked_until = 9999-12-31 23:59:59.9999999` means level 3 (admin unlock required).

**2. Unlock every active admin, all methods:**

```sql
BEGIN TRANSACTION;

UPDATE s SET
    s.[lock_level] = 0,
    s.[locked_until] = NULL,
    s.[unlocked_by] = NULL,             -- NULL marks a database unlock (not done through the app)
    s.[unlocked_at] = SYSUTCDATETIME()
FROM [user_auth_state] s
JOIN [users] u ON u.[id] = s.[user_id]
WHERE u.[role] = 'ADMIN' AND u.[active] = 1;

-- Check the row count, then:
COMMIT;      -- or ROLLBACK;
```

To unlock one person only, replace the `WHERE` clause with `WHERE s.[user_id] = <id>`.

**3. Write down** who ran it, when, and why: the database unlock leaves `unlocked_by` empty, so the reason isn't recorded anywhere else.

**What it does:** the same as the app's unlock. The lock ends, and failures and short-term blocks before `unlocked_at` no longer count. The next lock starts again at level 1 (15 minutes).

## Locked unknown identifiers

An email or cashier ID that doesn't belong to anyone can also reach level 3. That lock exists only in `login_attempts` (the `lock_level` and `locked_until` columns) and affects nobody real. It ages out after 90 days with the rest of `login_attempts`; there's nothing to unlock. If a real account is later created with that email, it isn't affected: real accounts use `user_auth_state`.

## After recovery

- Check the WARN logs for `LOGIN_PROTECTION_ALERT`, and run the monitor query pack (`docs/2026-11-11-login-protection-monitor-queries.md`, queries 2 and 4) to find what caused the locks.
- If one network address caused it, check `security.login-protection.trusted-proxies`. Behind an unconfigured proxy, every till shares one address.
