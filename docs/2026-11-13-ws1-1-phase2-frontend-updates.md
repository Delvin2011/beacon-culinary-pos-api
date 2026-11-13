# WS1-1 Phase 2: frontend updates still needed

For the frontend agent working on `feature/ws1-1-phase2-frontend`. The original Phase 2 brief is built (commits `e543ee3`, `018db3b`, `4526389`, `a02ac45`, `6dc1488`, `f282699`, 97 tests). This file lists what changed in the backend since that brief, and what to check against the full contract.

**Attach with this file:** `docs/2026-11-11-login-protection-api-contract.md` from POS_BE (branch `feature/ws1-1-phase2-enforcement`). It is authoritative, and you haven't received it yet. Everything so far was built from a summary.

Same working rules as before: show diffs for existing files and wait for confirmation; commit per step; do not push or merge.

## 1. New backend behaviour: admins can't unlock themselves (must do)

The backend now refuses self-unlock:

| Action on the signed-in admin's own row | Backend response |
|---|---|
| `POST /admin/staff/{id}/unlock` | **409** `{"error":"You can't unlock your own sign-in. Ask another admin to do it.","code":"SELF_UNLOCK"}`; lock unchanged |
| `POST /admin/staff/{id}/pin` or `/password` (or `PUT` with `pin`/`password`) | 200; the new credential is saved, **but the lock stays**; the response's `locks` still shows it |

**Frontend changes:**

1. **Hide the Unlock button on the signed-in admin's own row.** Compare the row's `id` with the signed-in user (from `/auth/me` or the token). This answers your open question: hide it.
2. **Handle 409 `SELF_UNLOCK`** from the unlock call: show the response's `error` text in the usual error style. Branch on `code`, not the text. Don't retry.
3. **After setting your own PIN or password, show the returned `locks`.** If it still says locked, say so, for example: "PIN changed. Your PIN sign-in is still locked until 09:39 — another admin can unlock it." Don't claim the reset unlocked anything.
4. **Tests:**
   - Unlock is hidden on your own row and shown on others (admin only).
   - A 409 `SELF_UNLOCK` shows the message and makes no retry.
   - After a self-reset whose response is still locked, the badge stays and the message says so.

## 1b. Your request is done: short-term blocks in staff records (must do)

The backend now sends `blocks` next to `locks`, exactly with the field names you proposed (contract section 2.8):

```json
"blocks": {
  "PIN":             { "blocked": true,  "blockedUntil": "2026-11-13T10:41:00Z" },
  "MANAGER_PIN":     { "blocked": false, "blockedUntil": null },
  "CHANGE_PASSWORD": { "blocked": false, "blockedUntil": null }
}
```

**What the backend sends:**
- `blocks` comes back in every response that has `locks`: the staff list and detail, create, update, set-PIN, set-password and unlock.
- `PIN.blocked` is true for a block on **any** till; `blockedUntil` is when the last one ends (UTC).
- A block clears with an unlock, a successful sign-in, or the 15 minutes passing.
- Everything is false or null in monitor and off mode.
- Burst-limit refusals (per network address) never appear.

**Frontend changes:**
1. Show the red badge and note only on rows with an active lock (`locks.*.locked`) or block (`blocks.*.blocked`). For example: "PIN blocked at a till until 12:41" or "Manager PIN blocked until 12:41", in local time.
2. Show **Unlock** only on those rows, and never on your own row (section 1). This replaces Unlock on every row.
3. If both `blocks.PIN` and `locks.PIN` are active, show both.
4. **Tests:**
   - the badge and Unlock appear only for a lock or a block
   - each block type gets its note
   - Unlock is hidden when nothing is active
   - a row is refreshed from the unlock response

## 2. Check against the full contract (verify; fix if not already so)

These are in the contract but weren't in the summary you built from:

| # | Contract point | What the frontend should do |
|---|---|---|
| 2.1 | **`/auth/me` can return 429** (the burst limit covers every `/auth/**` request) | Same as `/auth/refresh`: not a sign-out, no retry |
| 2.2 | **`/admin/authorize` and `/admin/authorize-session` can return 429 from the burst limit**, not only from manager-PIN blocks | The popup shows the countdown for any 429 there (`RATE_LIMITED`) |
| 2.3 | **Two kinds of 401 on the manager-PIN endpoints:** `{"error":"Invalid PIN."}` means a wrong PIN; an **empty** 401 means the access token expired | Skip refresh-and-resend only for the `Invalid PIN.` body. An empty 401 must still refresh, as before. Confirm your "wrong manager PIN no longer sent twice" fix keeps this distinction. |
| 2.4 | **Unlock errors:** 400 `{"scope":"…"}`, 404 empty, 409 `SELF_UNLOCK` | Show each plainly; no retry |
| 2.5 | **`lockedUntil` is a UTC instant** (`…Z`) | Display in local time (South Africa, UTC+2) |
| 2.6 | **`locks` is returned by** `GET /admin/staff`, `GET /admin/staff/{id}`, `POST /admin/staff`, `PUT /admin/staff/{id}`, `POST /admin/staff/{id}/pin`, `POST /admin/staff/{id}/password` and the unlock endpoint | Update the row from whichever response arrives; no extra reload needed |
| 2.7 | **Only `PASSWORD` and `PIN` appear in `locks`.** Short blocks (PIN per till, manager PIN, change password) are never listed | Don't expect other keys; tolerate `locks` being absent (an older backend) |
| 2.8 | **Validation runs before the lock check:** a malformed request gets 400, never 429 (except from the burst limit) | Client-side 6-digit validation already avoids this; keep showing the backend's 400 |
| 2.9 | **A refused attempt does not count as a failure** and doesn't extend the lock | Nothing to do. It confirms that memory-only countdowns are fine after a reload |

## 3. Investigate: automatic resubmission (should do)

A local test on 2026-11-13 recorded **55 failed password sign-ins** for one admin email in about 3 minutes, one every 2–2.5 seconds, from Edge on localhost. If those weren't deliberate clicks, something resent them.

- Check whether the password form submits on browser **autofill**, or on Enter repeating.
- Check whether any 401 path still **refreshes and resends** a sign-in request.
- In enforce mode, the 6th attempt now gets 429 and the button is disabled by the countdown. Confirm that **nothing is sent while the countdown runs**.

## 4. Optional UX

After two or three 401s on password sign-in, show a neutral hint: "Forgot your password? Ask a manager to reset it." It's safe for any email: it doesn't reveal whether the account exists. There's no self-service password reset.

## 5. Release notes for the frontend

- **The 6-digit PIN rule applies as soon as the backend is deployed, in every mode.** The frontend must be deployed **with or before** the backend release.
- The backend starts in **monitor** mode: no 429s and no locks until the owner sets `LOGIN_PROTECTION_MODE=enforce`. The staff page shows every lock as `false` until then.
- **To test locally against enforcement now:** the backend runs at `http://localhost:8080` in enforce mode. Five wrong passwords lock an email for 15 minutes; five wrong PINs from one till block that cashier there; an admin can't unlock their own row.

## 6. Out of scope (unchanged)

- Change-password screen: option (a); the shared handler is ready.
- Per-scope unlock in the UI: Unlock always unlocks everything.
