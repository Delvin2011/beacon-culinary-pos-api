# Login protection: API contract (WS1-1 Phase 2)

What the frontend can receive from the sign-in, manager-PIN and staff endpoints once login protection is in place. This contract is decision D2 in detail.

**Applies when:** responses marked *(enforce)* happen only when the server runs with `LOGIN_PROTECTION_MODE=enforce`. With `monitor` (the current default) or `off`, those responses never occur and every endpoint behaves exactly as before. The frontend should handle them now, so that switching enforcement on needs no frontend release.

## 1. General rules

### 1.1 The 429 response

Every refusal by login protection is **HTTP 429 Too Many Requests** with a JSON body of exactly three fields:

```json
{
  "code": "ACCOUNT_LOCKED",
  "message": "Too many failed attempts. Try again later.",
  "retryAfterSeconds": 900
}
```

| Field | Type | Meaning |
|---|---|---|
| `code` | string | `ACCOUNT_LOCKED` or `RATE_LIMITED`. Branch on this, never on `message`. |
| `message` | string | Generic text, safe to show as is. Never says whether an account exists. |
| `retryAfterSeconds` | integer or `null` | Seconds until the user may try again. `null` means the wait won't end by itself: a manager must unlock sign-in. |

**Header** `Retry-After: <seconds>` (whole seconds, at least 1) is present exactly when `retryAfterSeconds` is not null, and has the same value.

**The three bodies the server sends:**

```json
{"code":"ACCOUNT_LOCKED","message":"Too many failed attempts. Try again later.","retryAfterSeconds":900}
```
```json
{"code":"ACCOUNT_LOCKED","message":"Too many failed attempts. Ask a manager to unlock sign-in.","retryAfterSeconds":null}
```
```json
{"code":"RATE_LIMITED","message":"Too many attempts. Try again later.","retryAfterSeconds":42}
```

The numbers vary. `retryAfterSeconds` is `null` only for `ACCOUNT_LOCKED`.

### 1.2 Codes

| Code | Meaning | Typical wait | Suggested UI |
|---|---|---|---|
| `ACCOUNT_LOCKED` with a number | Too many failed attempts for this account (or email, or cashier). Sign-in is locked for a while. | 15 min the first time, 60 min the second | "Too many failed attempts. Try again in N minutes." Disable the sign-in button until then. |
| `ACCOUNT_LOCKED` with `null` | Third lock within 24 hours: only a manager can unlock it. | until unlocked | "Too many failed attempts. Ask a manager to unlock sign-in." No countdown. |
| `RATE_LIMITED` | Too many attempts from this device or network, or too many wrong manager PINs or old passwords. | seconds to 15 min | "Too many attempts. Try again in N seconds/minutes." |

### 1.3 When protection runs

The order of checks on each request:

1. **CORS.**
2. **Burst limit** per network address *(enforce)*. Applies to every `/auth/**` request and to `/admin/authorize` and `/admin/authorize-session`. On refusal: 429 `RATE_LIMITED`.
3. **Authentication** (bearer token) where the endpoint needs it: 401 or 403 as today.
4. **Request validation:** 400 with a field map, as today. An invalid request is rejected before any lock check, so a malformed request never returns 429 (except from the burst limit in step 2).
5. **Lock or block check** *(enforce)*: 429. The credential is **not** checked, so even a correct PIN or password gets 429 while locked.
6. **Credential check:** 401 or 200, as today.

### 1.4 What does not change

- A wrong email, password or PIN still returns **401 with an empty body**.
- A wrong manager PIN still returns **401 `{"error":"Invalid PIN."}`**.
- Responses never reveal whether an email or cashier exists. An unknown email that is "locked" gets exactly the same 429 responses as a real account.

### 1.5 PINs are exactly 6 digits

`POST /auth/pin-login`, `POST /admin/authorize` and `POST /admin/authorize-session` accept only a PIN of exactly **6 digits**. Anything else returns:

```http
HTTP/1.1 400 Bad Request
Content-Type: application/json

{"pin":"PIN must be 6 digits"}
```

This applies in every mode, including `off` and `monitor`. Previously 4 to 6 digits were accepted, with the message `PIN must be 4-6 digits`. Staff management already required exactly 6 digits, so every PIN set through it already complies.

### 1.6 CORS

The frontend is on a different origin, so the server lists `Retry-After` in `Access-Control-Expose-Headers`. Without that, browser JavaScript cannot read the header. Prefer `retryAfterSeconds` from the body anyway; it carries the same value. Credentials (the refresh cookie) work as before.

### 1.7 Limits behind one countdown

| Rule | Counted per | Triggers on | Response | Wait |
|---|---|---|---|---|
| Password account | email (known or not) | 5 failed sign-ins in 15 min | `ACCOUNT_LOCKED` | 15 min, then 60 min, then manager unlock |
| PIN from one device/network | cashier + network address | 5 failed PIN sign-ins in 15 min | `RATE_LIMITED` | until the oldest of those failures is 15 min old |
| PIN account ceiling | cashier, from anywhere | 20 failed PIN sign-ins in 60 min | `ACCOUNT_LOCKED` | 15 min, then 60 min, then manager unlock |
| Manager PIN | the signed-in user entering it | 5 wrong manager PINs in 15 min | `RATE_LIMITED` | until the oldest is 15 min old |
| Change password | the signed-in user | 5 wrong old passwords in 15 min | `RATE_LIMITED` | until the oldest is 15 min old |
| Burst | network address | more than 60 requests in a short burst (refills at 1 per second) | `RATE_LIMITED` | seconds |

A successful sign-in, or a manager unlock, resets the failure counts for that user and method. A lock level drops back after a successful sign-in or after 24 hours without a new lock. These values are server settings and may be tuned; the frontend should rely only on `code` and `retryAfterSeconds`.

## 2. Endpoints

### 2.1 `POST /auth/login` (public)

**Request**

```json
{"email":"chef@example.com","password":"…"}
```

| Status | Headers | Body | When |
|---|---|---|---|
| 200 | `Set-Cookie: refreshToken=…; Path=/auth/refresh; HttpOnly; …` | `{"token":"<access JWT>"}` | Correct email and password |
| 400 | | `{"email":"Email is required"}`, `{"password":"Password is required"}` or other field messages | Validation failed |
| 401 | | empty | Wrong password, unknown email, deactivated account, or a PIN-only account |
| 429 | `Retry-After: 900` | `{"code":"ACCOUNT_LOCKED","message":"Too many failed attempts. Try again later.","retryAfterSeconds":900}` | *(enforce)* 5 failures for this email in 15 min. Also for unknown emails. |
| 429 | none | `{"code":"ACCOUNT_LOCKED","message":"Too many failed attempts. Ask a manager to unlock sign-in.","retryAfterSeconds":null}` | *(enforce)* third lock within 24 h |
| 429 | `Retry-After: n` | `{"code":"RATE_LIMITED","message":"Too many attempts. Try again later.","retryAfterSeconds":n}` | *(enforce)* burst limit |

### 2.2 `POST /auth/pin-login` (public)

**Request**

```json
{"cashierId":12,"pin":"123456"}
```

| Status | Headers | Body | When |
|---|---|---|---|
| 200 | `Set-Cookie: refreshToken=…` | `{"token":"<access JWT>"}` | Correct PIN |
| 400 | | `{"pin":"PIN must be 6 digits"}` or `{"cashierId":"cashierId is required"}` | Validation failed |
| 401 | | empty | Wrong PIN, unknown cashier, or deactivated |
| 429 | `Retry-After: n` | `{"code":"RATE_LIMITED","message":"Too many attempts. Try again later.","retryAfterSeconds":n}` | *(enforce)* 5 failures for this cashier from this network address in 15 min; or the burst limit |
| 429 | `Retry-After: 900` | `{"code":"ACCOUNT_LOCKED","message":"Too many failed attempts. Try again later.","retryAfterSeconds":900}` | *(enforce)* 20 failures for this cashier from anywhere in 60 min |
| 429 | none | `{"code":"ACCOUNT_LOCKED","message":"Too many failed attempts. Ask a manager to unlock sign-in.","retryAfterSeconds":null}` | *(enforce)* third lock within 24 h |

The till should keep the cashier tile selected and show the countdown. Another cashier can still sign in on the same till unless the burst limit applies.

### 2.3 `POST /auth/refresh` (public, uses the `refreshToken` cookie)

| Status | Headers | Body | When |
|---|---|---|---|
| 200 | | `{"token":"<access JWT>"}` | Valid refresh token, active user, not locked |
| 400 | | Spring's default error body | No `refreshToken` cookie (unchanged) |
| 401 | | empty | Invalid or expired token, deactivated user, or *(enforce)* the user's password or PIN sign-in is currently locked |
| 429 | `Retry-After: n` | `RATE_LIMITED` body | *(enforce)* burst limit |

**On 401, send the user to the sign-in screen,** as today. If the account is locked, that screen then shows the 429 lock message. An existing access token keeps working until it expires (up to 15 minutes).

### 2.4 `GET /auth/me` (bearer token)

Unchanged, except *(enforce)* the burst limit can return 429 `RATE_LIMITED`.

### 2.5 `POST /admin/authorize` and `POST /admin/authorize-session` (bearer token: CASHIER or ADMIN)

**Request**

```json
{"pin":"123456"}
```

| Status | Headers | Body | When |
|---|---|---|---|
| 200 | | `{"authorizationToken":"…","adminId":3,"adminName":"…","expiresAt":"…"}` (authorize) or `{"sessionToken":"…","adminId":3,"expiresAt":"…"}` (authorize-session) | An active admin has this PIN |
| 400 | | `{"pin":"PIN must be 6 digits"}` | Validation failed |
| 401 | | `{"error":"Invalid PIN."}` | No active admin has this PIN (unchanged) |
| 401 / 403 | | empty | Missing or invalid bearer token, or not CASHIER/ADMIN (unchanged) |
| 429 | `Retry-After: n` | `{"code":"RATE_LIMITED","message":"Too many attempts. Try again later.","retryAfterSeconds":n}` | *(enforce)* the signed-in user entered 5 wrong manager PINs in 15 min; or the burst limit |

Many wrong manager PINs across all tills (20 in 60 min) raise a server alert but never refuse a request.

### 2.6 `POST /users/{id}/change-password` (bearer token, own account)

**Request**

```json
{"oldPassword":"…","newPassword":"…"}
```

| Status | Headers | Body | When |
|---|---|---|---|
| 200 | | empty | Old password correct, new one valid |
| 400 | | `{"newPassword":"Password must be between 6 and 25 characters."}` | New password invalid |
| 401 | | empty | Old password wrong |
| 403 | | empty | `{id}` is not the signed-in user |
| 429 | `Retry-After: n` | `{"code":"RATE_LIMITED","message":"Too many attempts. Try again later.","retryAfterSeconds":n}` | *(enforce)* 5 wrong old passwords in 15 min. Signing in is not affected. |

### 2.7 `POST /admin/staff/{id}/unlock` (bearer token: ADMIN), new

Unlocks sign-in for a staff member: ends account locks and short-term blocks.

**Request:** no body (all methods), or one scope:

```json
{"scope":"PIN"}
```

| `scope` | Ends |
|---|---|
| `PASSWORD` | Email/password sign-in lock |
| `PIN` | PIN sign-in lock, and PIN blocks from any device |
| `MANAGER_PIN` | Blocks on this user entering manager PINs |
| `CHANGE_PASSWORD` | Blocks on this user changing their password |
| *(no body)* | All four |

| Status | Body | When |
|---|---|---|
| 200 | The staff member (see 2.8), with the updated `locks` | Unlocked (also when nothing was locked) |
| 400 | `{"scope":"Scope must be one of PASSWORD, PIN, MANAGER_PIN, CHANGE_PASSWORD."}` | Unknown scope |
| 404 | empty | No such staff member |
| 409 | `{"error":"You can't unlock your own sign-in. Ask another admin to do it.","code":"SELF_UNLOCK"}` | `{id}` is the signed-in admin |
| 401 / 403 | empty | Not signed in, or not ADMIN |

This works in every mode. Setting a new PIN (`POST /admin/staff/{id}/pin`, or `PUT /admin/staff/{id}` with a `pin`) also unlocks `PIN`. Setting a new password (`POST /admin/staff/{id}/password`, or `PUT` with a `password`) also unlocks `PASSWORD` and `CHANGE_PASSWORD`.

**No self-unlock.** An admin can't clear their own locks:
- Unlocking yourself returns the 409 above.
- Setting your own PIN or password through staff management succeeds (200, the new credential is saved), but **your lock stays**. The response's `locks` still shows it.
- Another admin can unlock you, or reset your PIN or password, as usual.

Frontend: hide the Unlock button on the signed-in admin's own row, and show the 409 `message` if it ever arrives.

### 2.8 Staff list and detail: `GET /admin/staff`, `GET /admin/staff/{id}` (ADMIN)

Each staff member gains a `locks` object (account locks) and a `blocks` object (short-term blocks). Every other field is unchanged.

```json
{
  "id": 12,
  "name": "Thandi",
  "email": null,
  "role": "CASHIER",
  "active": true,
  "hasPassword": false,
  "hasPin": true,
  "locks": {
    "PASSWORD": {"locked": false, "lockedUntil": null, "requiresAdminUnlock": false},
    "PIN": {"locked": true, "lockedUntil": "2026-11-12T09:15:00Z", "requiresAdminUnlock": false}
  },
  "blocks": {
    "PIN": {"blocked": true, "blockedUntil": "2026-11-12T08:41:00Z"},
    "MANAGER_PIN": {"blocked": false, "blockedUntil": null},
    "CHANGE_PASSWORD": {"blocked": false, "blockedUntil": null}
  }
}
```

**`locks`: account locks** (the 15-minute, 60-minute and admin-unlock escalation). Only `PASSWORD` and `PIN` appear.

| Field | Meaning |
|---|---|
| `locked` | Sign-in by this method is locked now |
| `lockedUntil` | UTC instant the lock ends; `null` when not locked or when `requiresAdminUnlock` is true |
| `requiresAdminUnlock` | The lock only ends with an unlock (2.7) |

**`blocks`: short-term blocks.** Too many failures in 15 minutes; a block ends by itself when they age out.

| Key | `blocked` is true when |
|---|---|
| `PIN` | This person entered 5+ wrong PINs at a till within 15 minutes, on **any** till. The cashier can still sign in at other tills. |
| `MANAGER_PIN` | This person, as the one entering manager PINs, entered 5+ wrong ones within 15 minutes |
| `CHANGE_PASSWORD` | This person entered 5+ wrong old passwords within 15 minutes |

| Field | Meaning |
|---|---|
| `blocked` | A block is active now |
| `blockedUntil` | UTC instant the block ends if nothing else happens: for `PIN`, the end of the **last** till block. `null` when not blocked. |

**Ending a block:**
- an unlock (2.7) for that scope, or with no scope
- a successful sign-in by that method
- the 15 minutes passing

A person can have both a PIN block (`blocks.PIN`) and a PIN account lock (`locks.PIN`); show both. Burst-limit refusals belong to a network address, not a person, so they never appear here.

**Where they're returned:** both objects come back from `GET /admin/staff`, `GET /admin/staff/{id}`, `POST /admin/staff`, `PUT /admin/staff/{id}`, `POST /admin/staff/{id}/password`, `POST /admin/staff/{id}/pin` and the unlock endpoint. In `monitor` and `off` mode every lock and block is `false`.

**Suggested UI:**
- a "Locked" badge for `locks`, with the end time or "needs unlock"
- a "Blocked" note for `blocks` (for example "PIN blocked at a till until 10:41", in local time)
- an **Unlock** button (2.7) only on rows with a lock or block, never on your own row

## 3. Frontend checklist

- [ ] Handle 429 on sign-in, PIN sign-in, manager PIN and change password: branch on `code`; show `message`; count down `retryAfterSeconds`, or show "ask a manager" when it is `null`.
- [ ] Don't retry automatically on 429.
- [ ] On 401 from `/auth/refresh`, go to the sign-in screen (unchanged).
- [ ] PIN pads accept exactly 6 digits. Validate before sending.
- [ ] Staff page: show `locks`; add the Unlock action (2.7).
- [ ] Read `retryAfterSeconds` from the body. The `Retry-After` header is also readable cross-origin.
