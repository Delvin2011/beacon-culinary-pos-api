# Tills, per-till shifts, sessions and lock: API contract (O2)

What the backend sends and expects once several POS tills can trade at the same time, each with its own drawer and shift. It builds on:

- the login-protection contract (`docs/2026-11-11-login-protection-api-contract.md`)
- the device registration contract (`docs/2026-11-13-device-registration-api-contract.md`, WS1-1b)
- the O1 resilience contract (`docs/2026-11-16-o1-resilience-api-contract.md`)

All three still apply except where this document changes them.

**Status:** the contract to build against. Nothing here is implemented yet.

**The goal:**
- Up to three registered tills (T1, T2, T3) trade at once, each with its own shift, float and drawer.
- A cashier has one open shift, on one till, and one active POS session.
- A cashier can lock a till and come back to it; nobody else can sell on their shift except an ADMIN.
- The server knows which sessions are live, so a replaced, locked, idle or force-closed session stops working.

## Contents

1. Concepts
2. Settings
3. Deployment notes (everyone signs in again)
4. Sessions and the `sid` claim
5. Sign-in on a till: the rules
6. Error codes
7. New endpoints
8. Changed endpoints
9. Shifts per till: which shift a request uses
10. Force close, uncounted shifts and sign-off
11. Day close and reports
12. Refunds and voids on another till
13. Frontend checklist
14. Later steps (O3 to O5)

## 1. Concepts

| Term | Meaning |
|---|---|
| **Till** | A registered POS device (WS1-1b), identified by the `deviceToken` cookie. Each active till has a **till code** (`T1`, `T2`, …) and a name (`Till 1 (front)`). |
| **NULL till** | Every browser **without** a valid till registration. They all share one "unregistered till": at most one open shift between them, exactly as before O2. Can be switched off (`require-registered`, section 2). |
| **This till** | The till of the browser making the request, taken **only** from the `deviceToken` cookie. A revoked, expired or unknown cookie counts as the NULL till. Nothing in the request body or headers chooses the till. |
| **Shift** | One cashier's float and drawer on one till. A till has at most one open shift; a cashier has at most one open shift. |
| **Session** | One sign-in, from the moment it succeeds until it ends. Every access and refresh token carries its session ID (`sid`). |
| **Session kind** | `POS`: a CASHIER or ADMIN PIN sign-in, or any sign-in on a registered till. `STATION`: a PIN sign-in by KITCHEN, BARISTA or HEAD_CHEF. `BACK_OFFICE`: a password sign-in on a browser that isn't a registered till. |
| **Locked till** | A registered till with an open shift whose owner has no active POS session on it (the owner locked it, went idle, or was replaced). |

## 2. Settings

Application settings, validated at startup, each overridable by environment variable. The frontend reads the ones it needs from `GET /pos/till-status` (7.1) and `GET /admin/devices/config` (8.1).

| Setting | Default | Environment variable | Meaning |
|---|---|---|---|
| `pos.tills.max-active` | `3` | `POS_TILLS_MAXACTIVE` | Most active tills at once. Registering a new till beyond it: 409 `TILL_LIMIT_REACHED`. At least 1. |
| `pos.tills.require-registered` | `false` | `POS_TILLS_REQUIREREGISTERED` | When `true`, the POS needs a registered till: shift open, sales, adjustments and CASHIER/ADMIN PIN sign-in return 403 `DEVICE_NOT_REGISTERED` from any other browser. When `false`, unregistered browsers share the NULL till. |
| `pos.session.idle-lock-minutes` | `5` | `POS_SESSION_IDLELOCKMINUTES` | For the frontend: lock the till after this many minutes without user activity. The server doesn't enforce it. |
| `pos.session.idle-expiry-minutes` | `30` | `POS_SESSION_IDLEEXPIRYMINUTES` | Server backstop: a POS session with no activity (section 4.3) for this long ends with `IDLE`. Must be at least `idle-lock-minutes`. |
| `pos.session.sweep-enabled` | `true` | `POS_SESSION_SWEEP_ENABLED` | Runs the once-a-minute sweep that records idle (`IDLE`) and expired (`EXPIRED`) sessions. Refresh and POS writes treat an idle session as ended either way; the sweep only records it. Leave it on. |

**`require-registered` and `security.device-trust.mode` are independent.** The device-trust mode (WS1-1b) decides whether the staff list, POS tiles and manager-PIN approval need a registered till; `require-registered` decides whether selling does. The till is recognised from its cookie in every device-trust mode, including `off`. Recommended production setting: device-trust `required` and `require-registered=true`.

**Switching `require-registered` on** while the NULL till has an open shift: that shift can still be closed (`POST /shifts/{id}/close`) or force-closed (10.1), but nothing more can be sold on it.

## 3. Deployment notes (everyone signs in again)

Tokens issued before O2 have no session ID. After the deploy:
- `POST /auth/refresh` with such a token returns 401 `SESSION_ENDED` with `details.reason = "EXPIRED"`.
- POS writes with such an access token return 401 `SESSION_ENDED` (same details).

So **every user signs in once more** after the deploy. There is no grace period.

- **Deploy after day close**, when no shift is open.
- **Tell site managers beforehand** that tills and back-office users will need to sign in again.
- Any shift left open at deploy time stays open and attaches to the NULL till (it has no till). Its owner can still close it from any browser.
- Tills already registered get till codes `T1`, `T2`, … in the order they were registered. If more than `max-active` are already active, they all keep working; only new registrations are refused until some are revoked.

## 4. Sessions and the `sid` claim

### 4.1 Claims

Every sign-in (`/auth/login`, `/auth/pin-login`) starts a session. Its ID goes into both tokens:

| Claim | Value |
|---|---|
| `sid` | **New.** The session ID (UUID). Kept on refresh. |
| `amr`, `auth_time`, `dev` | Unchanged (WS1-1b). |

The frontend doesn't need to read the claims.

### 4.2 When a session ends

| `end_reason` | Cause |
|---|---|
| `LOGOUT` | `POST /auth/logout` with the session's access token (8.4). |
| `LOCK` | `POST /pos/lock` (7.2). |
| `REPLACED` | A newer POS sign-in by the same user (anywhere), or by anyone on the same till (section 5). |
| `IDLE` | A POS session with no activity for `idle-expiry-minutes`. |
| `ADMIN` | An admin revoked the till (or its browser) the session was on. |
| `SHIFT_FORCE_CLOSED` | An admin force-closed the user's shift (10.1); all the owner's POS sessions end. |
| `EXPIRED` | The refresh token's lifetime (7 days) passed, or the token has no session (section 3). |

`STATION` and `BACK_OFFICE` sessions end only with `LOGOUT`, `ADMIN` (when on a revoked till) or `EXPIRED`. They are never replaced, locked or idled out.

### 4.3 Activity (for idle expiry)

A POS session's "last seen" time moves only on:
- the sign-in itself;
- the POS writes listed in 4.4.

Refreshing tokens, polling, reading the menu and server-sent events **don't** count, because the till does them by itself. It is updated at most once a minute per session.

So a POS session whose till hasn't recorded a sale, adjustment or shift action for 30 minutes ends with `IDLE`. The frontend's 5-minute auto-lock normally ends it long before that.

### 4.4 Where an ended session is refused

| Request | Session ended (or token without `sid`) |
|---|---|
| `POST /auth/refresh` | 401 `SESSION_ENDED` |
| `POST /orders` | 401 `SESSION_ENDED` |
| `POST /orders/{id}/adjustments` | 401 `SESSION_ENDED` |
| `POST /orders/{id}/mark-print-failed` | 401 `SESSION_ENDED` |
| `POST /shifts/open` | 401 `SESSION_ENDED` |
| `POST /shifts/{id}/close` | 401 `SESSION_ENDED` |
| Every other request | Works until the access token expires (at most 15 minutes) |

**O1 retries still work after a session ends.** A retry of a sale or adjustment **that already happened** (same `clientOrderId` or `clientRequestId`, same content) returns the stored result with `Idempotent-Replay: true`, even from an ended session. The session is checked only before something **new** is recorded. So a till that was replaced mid-sale can still learn the sale's outcome; it can't make a new one.

**Order of checks on a POS write:**
1. Authentication and role (401, 403).
2. O1 replay lookup (200 with the stored result).
3. Session active (401 `SESSION_ENDED`).
4. `require-registered` (403 `DEVICE_NOT_REGISTERED`).
5. This till's open shift (409 `NO_OPEN_SHIFT`).
6. Shift owner or ADMIN (409 `TILL_IN_USE`).
7. The endpoint's own rules.

**Example: 401 session ended**

```json
{"error": "You were signed out because you signed in on another till.", "code": "SESSION_ENDED", "details": {"reason": "REPLACED"}}
```

`details.reason` is one of the `end_reason` values in 4.2. The frontend should **not** try to refresh after `SESSION_ENDED` (it would fail too). It should drop the tokens and show the sign-in screen with a message for the reason. The other 401s (bad or expired token) stay empty, as today.

## 5. Sign-in on a till: the rules

These apply to sign-ins that start a **POS** session (CASHIER or ADMIN PIN, or any sign-in on a registered till). They run **after** the PIN or password has been checked, so a wrong PIN never reveals anything about shifts. In the order checked:

| # | Situation | Result |
|---|---|---|
| 1 | `require-registered=true`, CASHIER or ADMIN PIN sign-in, browser isn't a registered till | 403 `DEVICE_NOT_REGISTERED` (checked **before** the PIN, as in WS1-1b) |
| 2 | The user has an open shift on **another** till (the NULL till counts as a till), and isn't an ADMIN | 409 `SHIFT_OPEN_ON_OTHER_TILL` |
| 3 | This is a registered till with an open shift, and the user is neither its owner nor an ADMIN | 409 `TILL_LOCKED` |
| 4 | Otherwise | 200. Then the user's other active POS sessions end (`REPLACED`), and so does any other active POS session on this till (`REPLACED`). |

Notes:
- **ADMIN is exempt from rule 2**, so a manager can sign in at any till to unlock, override or force-close, even with their own shift open elsewhere. An ADMIN is **not** exempt from the one-open-shift-per-cashier rule when opening a shift (9.2).
- **Rule 3 covers a locked till and a till in use.** On a till with an open shift, only its owner (which resumes the shift) or an ADMIN can sign in.
- **The NULL till has no rule 3.** On unregistered browsers, anyone may sign in as before; selling on someone else's shift still gets 409 `TILL_IN_USE`.
- **Rule 4 never closes a shift.** The till's open shift stays open, and every sale and adjustment records the user who actually made it (`cashierId` on orders, `requestedBy` on adjustments).
- `STATION` and `BACK_OFFICE` sign-ins skip rules 2 to 4, and never end other sessions.
- Refusals at rule 2 or 3 are recorded in the sign-in log with outcome `SHIFT_OPEN_ON_OTHER_TILL` or `TILL_LOCKED`. They are **not** failed sign-ins: they never count toward lockout.

**Example: 409 shift open on another till**

```json
{"error": "You have a shift open on T2 (Till 2 (back)). Close it there before signing in here.", "code": "SHIFT_OPEN_ON_OTHER_TILL", "details": {"tillCode": "T2", "tillName": "Till 2 (back)"}}
```

For a shift on the NULL till: `"details": {"tillCode": null, "tillName": "Unregistered till"}`.

**Example: 409 till locked**

```json
{"error": "This till is in use by Thandi Mokoena (T1). Only Thandi or a manager can sign in.", "code": "TILL_LOCKED", "details": {"ownerName": "Thandi Mokoena", "tillCode": "T1"}}
```

## 6. Error codes

All use `ErrorDto` `{error, code, details?}` (`details` only when there is extra data). **Branch on `code`**; `error` is display text and may change.

| Status | `code` | Where | `details` |
|---|---|---|---|
| 401 | `SESSION_ENDED` | Refresh and POS writes (4.4) | `reason` |
| 403 | `DEVICE_NOT_REGISTERED` | `require-registered=true`: shift open, sales, adjustments, CASHIER/ADMIN PIN sign-in | none |
| 403 | `REAUTH_REQUIRED` | Force close, late count, confirm no count (as for device management: a password sign-in within 30 min) | none |
| 409 | `TILL_LIMIT_REACHED` | Registering a new till (`POST /admin/devices`) | `maxActive`, `activeCount` |
| 409 | `TILL_CODE_IN_USE` | Editing a till's code to one another active till has | `deviceId`, `deviceName` |
| 409 | `SHIFT_OPEN_ON_OTHER_TILL` | Sign-in (5), shift open (9.2) | `tillCode`, `tillName` |
| 409 | `SHIFT_ALREADY_OPEN` | Shift open when this till, or this cashier on this till, already has one. **Code is new**; the 409 itself isn't. | `shiftId`, `ownerName`, `tillCode` |
| 409 | `TILL_LOCKED` | Sign-in (5) | `ownerName`, `tillCode` |
| 409 | `NO_OPEN_SHIFT` | Sale or adjustment with no open shift **on this till** (meaning narrowed) | none |
| 409 | `TILL_IN_USE` | Sale **or adjustment** on this till's shift by someone who isn't its owner or an ADMIN (adjustments are new) | none |
| 409 | `SHIFT_ALREADY_CLOSED` | Close or force close of a closed shift. **Code is new**; the 409 itself isn't. | none |
| 409 | `SHIFT_UNCOUNTED` | Sign-off of an uncounted shift without a count or `confirmNoCount` (10.3) | `shiftId` |
| 409 | `SHIFT_NOT_UNCOUNTED` | Late count of a shift that isn't uncounted (10.2) | none |
| 409 | `ALREADY_SIGNED_OFF`, `DAY_CLOSED` | Late count of a signed-off shift, or of a shift whose day has a Z (existing codes, `EndOfDayErrorDto`) | as today |

## 7. New endpoints

### 7.1 `GET /pos/till-status`

What the POS sign-in screen shows: "This till: T1 · Till 1 (front)", and who holds its shift.
- **Public** (no sign-in), so it can be read before anyone signs in.
- Send credentials so the `deviceToken` cookie goes with it.
- Till and shift details are returned **only to a registered till**. Any other browser gets `registered: false` and `shift: null`, so owner names are never shown on an unregistered browser.
- Not rate-limited: it's a read, like `GET /health`. The burst limit covers only sign-in (`/auth/**`) and manager-PIN approval.

**Response 200, registered till with a locked shift:**

```json
{
  "registered": true,
  "tillCode": "T1",
  "tillName": "Till 1 (front)",
  "shift": {
    "shiftId": 412,
    "ownerId": 17,
    "ownerName": "Thandi Mokoena",
    "openedAt": "2026-11-18T05:58:12Z",
    "locked": true
  },
  "idleLockMinutes": 5,
  "requireRegistered": false
}
```

**Registered till, no open shift:**

```json
{"registered": true, "tillCode": "T2", "tillName": "Till 2 (back)", "shift": null, "idleLockMinutes": 5, "requireRegistered": false}
```

**Any other browser (no cookie, or an unknown, expired or revoked one):**

```json
{"registered": false, "tillCode": null, "tillName": null, "shift": null, "idleLockMinutes": 5, "requireRegistered": true}
```

| Field | Meaning |
|---|---|
| `shift.locked` | `true` when the owner has no active POS session on this till. `false` means the owner is signed in here right now. |
| `requireRegistered` | When `true` and `registered` is `false`, the POS can't be used here: show "This till isn't registered. Ask a manager." |

### 7.2 `POST /pos/lock`

Locks this till: ends the caller's session (`LOCK`) and clears the refresh cookie. **The shift stays open.**
- CASHIER or ADMIN, with a bearer token.
- No body.
- **Response: 204**, with the same `Set-Cookie` clearing `refreshToken` as `/auth/logout`.
- Idempotent: an already-ended session also gets 204.
- Works on the NULL till too, where it just ends the session.

Afterwards the till shows the sign-in screen with "Locked by Thandi". `GET /pos/till-status` shows `locked: true`. Only the owner (resume) or an ADMIN can sign in (section 5).

### 7.3 `POST /admin/shifts/{id}/force-close`

An ADMIN closes an abandoned shift on any till. See section 10.

## 8. Changed endpoints

### 8.1 Device administration (`/admin/devices`)

**Till code (R1).**
- Every till in the device shape (WS1-1b 6.2) gains `tillCode`.
- A **new** till gets the lowest free code (`T1`, then `T2`, …), counting active tills only. A revoked till keeps its code in the list, but the code becomes free for a new till.
- A browser joining an existing till keeps the till's code.

```json
{
  "id": "6f1c2a0e-3d5b-4b8e-9a51-1f2c3d4e5f60",
  "name": "Till 1 (front)",
  "tillCode": "T1",
  "type": "POS",
  "serialNumber": "PF3X9K2B",
  "assetTag": "BC-0042",
  "status": "ACTIVE",
  "...": "other fields as in WS1-1b"
}
```

**`POST /admin/devices`.**
- A **new** till beyond `max-active` gets 409 `TILL_LIMIT_REACHED`.
- Joining an existing active till (same serial) doesn't count, and is never refused for the limit.
- Checked after the existing serial and asset-tag rules.

```json
{"error": "This site already has 3 active tills, the most allowed. Revoke one before registering another.", "code": "TILL_LIMIT_REACHED", "details": {"maxActive": 3, "activeCount": 3}}
```

**`PUT /admin/devices/{id}`.**
- New optional field `tillCode`. Omitted or `null` means unchanged.
- Trimmed and upper-cased, 1–10 characters from `A–Z`, `0–9` and `-`. Otherwise 400 field map `{"tillCode": "..."}`.
- A code another **active** till has: 409 `TILL_CODE_IN_USE`.
- Needs the recent password sign-in, as for every edit.

```json
{"name": "Till 1 (front)", "serialNumber": "PF3X9K2B", "assetTag": "BC-0042", "tillCode": "T1"}
```

```json
{"error": "Another active till already uses T2.", "code": "TILL_CODE_IN_USE", "details": {"deviceId": "0b7e…", "deviceName": "Till 2 (back)"}}
```

Changing a till's code or name doesn't change shifts already opened on it: each shift keeps the code and name it opened with (section 11).

**Revoking a till or a browser** (6.6, 6.7) also ends every active session on it (`ADMIN`). A shift open on that till stays open: an ADMIN force-closes it (10.1), or the owner closes it from anywhere.

**`GET /admin/devices/config`** gains two fields:

```json
{"mode": "optional", "multiBrowserEnabled": false, "requireSerial": true, "maxActiveTills": 3, "requireRegistered": false}
```

### 8.2 `POST /auth/login` and `POST /auth/pin-login`

- **Tokens carry `sid`** (4.1).
- **New responses:** 409 `SHIFT_OPEN_ON_OTHER_TILL`, 409 `TILL_LOCKED` (section 5).
- **`/auth/pin-login` with `require-registered=true`:** 403 `DEVICE_NOT_REGISTERED` for CASHIER, ADMIN and unknown IDs from a browser that isn't a registered till, in every device-trust mode. Same order and rules as WS1-1b section 4: checked before the PIN, never counted as a failure.
- **A successful POS sign-in ends other sessions:** the user's other POS sessions, and any other POS session on this till (`REPLACED`). Those tills see `SESSION_ENDED` on their next refresh or sale.

### 8.3 `POST /auth/refresh`

- 401 `SESSION_ENDED` (ErrorDto, `details.reason`) when the token's session has ended, or the token has no `sid`.
- Other failures stay **401 with an empty body**, as today.
- On success the new access token keeps `sid`, `amr`, `auth_time` and `dev`.

### 8.4 `POST /auth/logout`

- **Now also ends the session** (`LOGOUT`) named by the `Authorization: Bearer` token, **if one is sent**. An expired access token is accepted for this (its signature is still checked).
- Without a bearer token it behaves exactly as today: clears the refresh cookie, ends nothing.
- Always 204. Idempotent.

**The frontend should send the access token on logout** (it previously didn't need to). Otherwise the session stays active on the server until it ends by itself.

### 8.5 Shifts

**`ShiftDto`** (open, close, current, force close) gains:

```json
{
  "id": 412,
  "cashierId": 17,
  "deviceId": "6f1c2a0e-3d5b-4b8e-9a51-1f2c3d4e5f60",
  "tillCode": "T1",
  "tillName": "Till 1 (front)",
  "openingFloat": 500.00,
  "openedAt": "2026-11-18T05:58:12",
  "closedAt": null,
  "status": "OPEN",
  "closingCash": null,
  "expectedCash": null,
  "variance": null,
  "varianceReasonCode": null,
  "varianceNote": null,
  "varianceAuthorizedById": null,
  "forceClosed": false,
  "cashCount": "COUNTED"
}
```

- On the NULL till, `deviceId` and `tillCode` are `null` and `tillName` is `"Unregistered till"`.
- `cashCount` is explained in 10.2. It is `"COUNTED"` for every normal close, and also while the shift is open.

**`POST /shifts/open`.** Opens a shift **on this till** (section 9).

| Situation | Response |
|---|---|
| `require-registered=true` and not a registered till | 403 `DEVICE_NOT_REGISTERED` |
| Session ended | 401 `SESSION_ENDED` |
| Today has a Z | 409 `DAY_CLOSED` (unchanged) |
| The caller has an open shift on **another** till (ADMIN included) | 409 `SHIFT_OPEN_ON_OTHER_TILL` |
| This till already has an open shift (anyone's, the caller's included) | 409 `SHIFT_ALREADY_OPEN` |
| Otherwise | 201 `ShiftDto` |

```json
{"error": "T1 already has an open shift (Thandi Mokoena).", "code": "SHIFT_ALREADY_OPEN", "details": {"shiftId": 412, "ownerName": "Thandi Mokoena", "tillCode": "T1"}}
```

**`GET /shifts/open`.**
- Now returns **this till's** open shift (404 when it has none), no longer "the" open shift.
- `OpenShiftDto` gains `tillCode` and `tillName`.
- Use `GET /pos/till-status` before sign-in.

```json
{"shiftId": 412, "owner": {"id": 17, "name": "Thandi Mokoena", "role": "CASHIER"}, "ownedByCaller": true, "openedAt": "2026-11-18T05:58:12Z", "openingFloat": 500.00, "tillCode": "T1", "tillName": "Till 1 (front)"}
```

**`GET /shifts/current`.** Unchanged: the caller's own open shift, on whichever till. Gains the `ShiftDto` till fields. A frontend can compare its `tillCode` with this till's.

**`POST /shifts/{id}/close`.**
- Unchanged rules: owner or ADMIN, from any browser.
- New: 401 `SESSION_ENDED`.
- 409 now carries `code: "SHIFT_ALREADY_CLOSED"`.

### 8.6 Orders and adjustments

**`POST /orders`.**
- Sells on **this till's** open shift. `NO_OPEN_SHIFT` now means "no open shift on this till".
- `TILL_IN_USE` when the caller isn't that shift's owner or an ADMIN.
- New: 403 `DEVICE_NOT_REGISTERED` (`require-registered`), 401 `SESSION_ENDED`.
- The order records this till.

**`POST /orders/{id}/adjustments`** (voids, refunds, discounts).
- Book to **this till's** open shift.
- **New: `TILL_IN_USE`.** Only that shift's owner or an ADMIN can process them, as for sales. The manager-PIN approval is still needed, as today.
- New: 403 `DEVICE_NOT_REGISTERED`, 401 `SESSION_ENDED`.

**`OrderDto`** gains `tillCode` (the till the sale was rung up on; `null` for the NULL till and for sales before O2):

```json
{"id": 9041, "orderNumber": 37, "shiftId": 412, "cashierId": 17, "tillCode": "T1", "...": "other fields unchanged"}
```

### 8.7 Admin shift views and sign-off

- **`GET /admin/shifts`** (list) and **`GET /admin/shifts/{id}/activity`** gain `tillCode`, `tillName`, `forceClosed` and `cashCount`.
- **`POST /admin/shifts/{id}/signoff`** gains an optional body field for uncounted shifts (10.3).

## 9. Shifts per till: which shift a request uses

### 9.1 The rules

| Rule | Enforced by |
|---|---|
| One open shift per till (the NULL till counts as one till) | Unique index on open shifts' till |
| One open shift per cashier (ADMIN included) | Unique index on open shifts' cashier |
| A sale or adjustment uses **this till's** open shift | The server, from the cookie |
| Only the shift's owner or an ADMIN sells or adjusts on it | `TILL_IN_USE` |

**The NULL till keeps today's behaviour.** SQL Server treats `NULL` values as equal in a unique index (unlike standard SQL). Limited to open shifts, the index therefore allows only **one** open shift without a till. All unregistered browsers share it, exactly as before O2. Tests cover this.

### 9.2 Opening at the same moment

Two cashiers opening on the same till at once, or one cashier opening on two tills at once: exactly one succeeds. The other gets `SHIFT_ALREADY_OPEN` or `SHIFT_OPEN_ON_OTHER_TILL`, never a 500.

### 9.3 Tills don't block each other

Each sale locks only its own till's shift row, so sales on T1, T2 and T3 run in parallel. Closing a shift still waits for that shift's in-flight sales (unchanged).

## 10. Force close, uncounted shifts and sign-off

### 10.1 `POST /admin/shifts/{id}/force-close`

For a shift whose owner can't close it: they left, their till was revoked, or the till is locked.
- ADMIN only.
- Needs a **password sign-in within 30 minutes** (else 403 `REAUTH_REQUIRED`, as for device management).
- Works for a shift on any till, the NULL till included, from any browser.

**Request:**

```json
{"countedCash": 1180.50, "reason": "Cashier went home ill; drawer counted by manager.", "varianceReasonCode": "CASH_COUNTING_ERROR"}
```

| Field | Rules |
|---|---|
| `reason` | Required, 1–255 characters. Stored on the shift as the force-close reason. |
| `countedCash` | Optional, ≥ 0. Omit when the drawer can't be counted now (10.2). |
| `varianceReasonCode` | Required when `countedCash` is given and differs from expected cash. One of `CASH_COUNTING_ERROR`, `THEFT_SUSPECTED`, `UNRECORDED_TRANSACTION`, `OTHER`. With `OTHER`, `reason` serves as the note. Ignored when there is no variance or no count. |

**What it does:**
- Same calculation as a normal close: expected cash = float + cash sales − cash refunds, snapshotted, under the same lock against in-flight sales.
- **The variance is approved by the ADMIN making the call.** Their recent password sign-in replaces the manager-PIN token a cashier's close needs. They are recorded as `closedBy` and, when there is a variance, `varianceAuthorizedBy`.
- The shift is marked `forceClosed: true`.
- **Every active POS session of the shift's owner ends** (`SHIFT_FORCE_CLOSED`), wherever it is.
- The till is free: anyone may sign in and open a shift there.

**Responses:**

| Situation | Response |
|---|---|
| Done | 200 `ShiftDto` |
| No recent password sign-in | 403 `REAUTH_REQUIRED` |
| Not an ADMIN | 403 (empty) |
| Unknown shift | 404 (empty) |
| Already closed | 409 `SHIFT_ALREADY_CLOSED` |
| Missing `reason`, negative `countedCash`, missing `varianceReasonCode` | 400 field map, e.g. `{"varianceReasonCode": "varianceReasonCode is required when countedCash differs from expected cash (1200.00)."}` |

**200 example, counted:**

```json
{"id": 412, "cashierId": 17, "deviceId": "6f1c…", "tillCode": "T1", "tillName": "Till 1 (front)", "openingFloat": 500.00, "openedAt": "2026-11-18T05:58:12", "closedAt": "2026-11-18T15:02:40", "status": "CLOSED", "closingCash": 1180.50, "expectedCash": 1200.00, "variance": -19.50, "varianceReasonCode": "CASH_COUNTING_ERROR", "varianceNote": "Cashier went home ill; drawer counted by manager.", "varianceAuthorizedById": 3, "forceClosed": true, "cashCount": "COUNTED"}
```

**200 example, not counted** (`countedCash` omitted):

```json
{"id": 412, "...": "as above", "closingCash": null, "expectedCash": 1200.00, "variance": null, "varianceReasonCode": null, "varianceNote": null, "varianceAuthorizedById": null, "forceClosed": true, "cashCount": "UNCOUNTED"}
```

### 10.2 Uncounted shifts: `cashCount`

| `cashCount` | Meaning | Counted cash and variance |
|---|---|---|
| `COUNTED` | Closed normally, or force-closed with a count | Set |
| `UNCOUNTED` | Force-closed without a count; **waiting** for a late count or a confirmed "no count" | Empty |
| `COUNTED_LATE` | Counted after the close (below) | Set |
| `NO_COUNT_CONFIRMED` | A manager confirmed it will never be counted, with a reason | Empty |

**Late count: `POST /admin/shifts/{id}/count`** (ADMIN, recent password sign-in).

```json
{"countedCash": 1195.00, "varianceReasonCode": "CASH_COUNTING_ERROR", "note": "Counted next morning from the sealed bag."}
```

| Field | Rules |
|---|---|
| `countedCash` | Required, ≥ 0 |
| `varianceReasonCode` | Required when it differs from the expected cash snapshotted at close |
| `note` | Required with `OTHER`; otherwise optional, at most 255 characters |

- Allowed while `cashCount` is `UNCOUNTED`, or `NO_COUNT_CONFIRMED` whose sign-off has since been revoked.
- Not allowed once the shift is signed off (409 `ALREADY_SIGNED_OFF`), or once its day has a Z (409 `DAY_CLOSED`).
- Otherwise 409 `SHIFT_NOT_UNCOUNTED`.
- Sets counted cash, variance (counted − expected at close), the reason, and the calling ADMIN as `varianceAuthorizedBy`. `cashCount` becomes `COUNTED_LATE`.
- 200 with the `ShiftDto`.
- It does **not** sign the shift off: sign it off as usual afterwards.

### 10.3 Sign-off of an uncounted shift

`POST /admin/shifts/{id}/signoff` (existing, ADMIN), with a new optional field:

```json
{"note": "Bag lost in transit; reported to head office.", "confirmNoCount": true}
```

| Shift's `cashCount` | Body | Result |
|---|---|---|
| `COUNTED`, `COUNTED_LATE` | as today | Signed off, as today |
| `UNCOUNTED` | without `confirmNoCount` | 409 `SHIFT_UNCOUNTED`: "Record the count or confirm there is no count." |
| `UNCOUNTED` | `confirmNoCount: true` and a non-blank `note` | Signed off. `cashCount` becomes `NO_COUNT_CONFIRMED`; the note is the reason. Needs a recent password sign-in (403 `REAUTH_REQUIRED`). |
| `UNCOUNTED` | `confirmNoCount: true`, no `note` | 400 `{"note": "note is required to confirm there is no count."}` |
| `NO_COUNT_CONFIRMED` (sign-off revoked) | as today | Signed off again, as today |

```json
{"error": "Shift #412 was force-closed without a cash count. Record the count or confirm there is no count.", "code": "SHIFT_UNCOUNTED", "details": {"shiftId": 412}}
```

**Close Day** treats a `NO_COUNT_CONFIRMED` shift as signed off (it is: confirming writes a sign-off). An `UNCOUNTED` shift blocks Close Day with the existing `SHIFT_NOT_SIGNED_OFF` blocker, whose message says it needs a count or a confirmed "no count".

### 10.4 Audit

Every force close, late count and "no count" confirmation is written to a new insert-only log (`shift_cash_count_events`), with:
- shift, action (`FORCE_CLOSED`, `LATE_COUNT`, `NO_COUNT_CONFIRMED`);
- counted cash, expected cash, variance and reason code (where they apply);
- the note or reason;
- the admin's ID and name, and the time (UTC).

Rows can't be updated or deleted (a trigger refuses it, as for Z snapshots). Sign-offs and revokes stay in the existing `shift_signoffs` log.

## 11. Day close and reports

**Close Day** is unchanged in its rules:
- blocked by **any** open shift on **any** till, the NULL till included;
- blocked by any shift that isn't signed off (`NO_COUNT_CONFIRMED` counts as signed off).

Blocker messages now name the till, e.g. `"Shift #412 (Thandi Mokoena, T1) is still open."`.

**Readings and sign-off stay per shift.** The Z still covers every shift of the trading day, on every till.

**Each shift keeps the till code and name it opened with**, so renaming or recoding a till later doesn't change past reports. Reports show `T1 · Till 1 (front)`, or `Unregistered till` for the NULL till.

**Till columns** (new, on shift-based reports):

| Report | Change |
|---|---|
| Cash-up & Variance | New column `till`; "Not counted" for `UNCOUNTED` and `NO_COUNT_CONFIRMED` shifts; their variance is left out of the totals, with a note saying how many |
| Cashier reading (shift reading) | Header line "Till" |
| X reading (today, live) | `till` column in the Shifts table; new **Tills** table (below) |
| Z reading (new Zs only) | `till` column in the Shifts table; new **Tills** table (below) |
| `GET /admin/shifts`, activity | `tillCode`, `tillName` (8.7) |

**New Z snapshot shape (Z definition version 2).**
- Zs closed before O2 keep version 1 and are returned exactly as stored (insert-only). The frontend must accept both versions.
- Version 2 adds, in the `shifts` table, a column `{"key": "till", "label": "Till", "type": "TEXT", "total": "NONE"}` after `cashier`.
- Version 2 also adds a new table section after `shifts`:

```json
{
  "key": "tills",
  "type": "TABLE",
  "title": "Tills",
  "columns": [
    {"key": "till", "label": "Till", "type": "TEXT", "total": "LABEL"},
    {"key": "shifts", "label": "Shifts", "type": "INT", "total": "SUM"},
    {"key": "openingFloat", "label": "Float", "type": "MONEY", "total": "SUM"},
    {"key": "cashSales", "label": "Cash sales", "type": "MONEY", "total": "SUM"},
    {"key": "cashRefunds", "label": "Cash refunds", "type": "MONEY", "total": "SUM"},
    {"key": "refundsOfOtherTills", "label": "of which other tills' sales", "type": "MONEY", "total": "SUM"},
    {"key": "expectedCash", "label": "Expected", "type": "MONEY", "total": "SUM"},
    {"key": "countedCash", "label": "Counted", "type": "MONEY", "total": "SUM"},
    {"key": "variance", "label": "Variance", "type": "VARIANCE", "total": "SUM"},
    {"key": "uncounted", "label": "Not counted", "type": "INT", "total": "SUM"}
  ],
  "slipColumns": ["till", "expectedCash", "variance"],
  "rows": [
    {"till": "T1 · Till 1 (front)", "shifts": 2, "openingFloat": 1000.00, "cashSales": 4210.00, "cashRefunds": 45.00, "refundsOfOtherTills": 0.00, "expectedCash": 5165.00, "countedCash": 5160.00, "variance": -5.00, "uncounted": 0},
    {"till": "T2 · Till 2 (back)", "shifts": 1, "openingFloat": 500.00, "cashSales": 1980.00, "cashRefunds": 60.00, "refundsOfOtherTills": 35.00, "expectedCash": 2420.00, "countedCash": 2420.00, "variance": 0.00, "uncounted": 0}
  ],
  "totals": {"till": "Total", "shifts": 3, "openingFloat": 1500.00, "cashSales": 6190.00, "cashRefunds": 105.00, "refundsOfOtherTills": 35.00, "expectedCash": 7585.00, "countedCash": 7580.00, "variance": -5.00, "uncounted": 0}
}
```

- The section uses the same table-section format as the Z's existing `shifts` table (`key`, `type`, `title`, `columns` with `total`, `rows`, `totals`, `slipColumns`).
- Rows are ordered by till code, with `Unregistered till` last; a till with no shift that day has no row.
- Counted cash and variance cover counted shifts only; `uncounted` says how many shifts have none.

## 12. Refunds and voids on another till

A cash refund or void books to the open shift of **the till processing it**, as today across shifts.

**Example:** a sale rung up on T1 (shift A) is refunded in cash on T2 (shift B):
- The cash leaves **T2's drawer**, so it counts in shift B's cash refunds and lowers B's expected cash.
- Shift A's cash sales don't change, so A's cash-up is unaffected.
- Day and Z totals are correct (one sale, one refund).
- T2's net cash for the day can be lower than its own sales, or negative. The Z's **Tills** table shows these refunds separately ("of which other tills' sales") so the difference is explained.
- The refund needs an open shift on T2 (else `NO_OPEN_SHIFT`), owned by the caller or with an ADMIN processing it (else `TILL_IN_USE`), plus the manager-PIN approval.
- Refunds to an account balance move no cash and affect no drawer.

The same applies if T1's shift is already closed or signed off: its figures never change after close.

## 13. Frontend checklist

- [ ] **Deploy:** expect everyone to sign in again after the deploy (section 3).
- [ ] **Sign-in screen:** read `GET /pos/till-status` (with credentials) and show:
  - "This till: T1 · Till 1 (front)" when `registered`;
  - "Locked by Thandi Mokoena" with a resume prompt when `shift.locked`;
  - "This till isn't registered. Ask a manager." when `requireRegistered` and not `registered`.
- [ ] **Handle on sign-in:**
  - 409 `TILL_LOCKED`: "In use by {ownerName}. Only they or a manager can sign in."
  - 409 `SHIFT_OPEN_ON_OTHER_TILL`: "You have a shift open on {tillCode}…"
  - 403 `DEVICE_NOT_REGISTERED`
- [ ] **Auto-lock:** after `idleLockMinutes` without user input, call `POST /pos/lock`, drop the tokens and show the sign-in screen.
- [ ] **A Lock button** on the POS: the same call.
- [ ] **`SESSION_ENDED` on refresh or a write:** don't retry or refresh; drop the tokens and show the sign-in screen with a message for `details.reason` (`REPLACED`: "You signed in somewhere else"; `IDLE`; `SHIFT_FORCE_CLOSED`: "A manager closed your shift"; `ADMIN`; `LOCK`; `EXPIRED`).
- [ ] **O1 sales whose outcome is unknown:** after signing in again, retry with the same `clientOrderId`. The replay works for the same cashier (or an ADMIN).
- [ ] **Logout:** send `Authorization: Bearer <access token>` on `POST /auth/logout`.
- [ ] **Shift open:** handle `SHIFT_ALREADY_OPEN` (show who holds the till) and `SHIFT_OPEN_ON_OTHER_TILL`.
- [ ] **Adjustments:** handle `TILL_IN_USE` (new there).
- [ ] **Device screens:**
  - show and edit `tillCode`;
  - handle `TILL_LIMIT_REACHED` ("3 of 3 tills in use: revoke one first") and `TILL_CODE_IN_USE`.
- [ ] **Admin shift screens:**
  - till column;
  - "Force close" (reason, optional count, variance reason; send to password sign-in on `REAUTH_REQUIRED`);
  - for `cashCount: "UNCOUNTED"`: "Record count" (10.2) or "Confirm no count" in sign-off (10.3).
- [ ] **Reports:** show the till column, "Not counted", and the Z **Tills** section; accept Z snapshots of version 1 and 2.

## 14. Later steps (O3 to O5)

Not part of O2:
- **O3:** offline grants and offline unlock values. Each grant will hang off a session (`user_sessions.id`), so it ends with its session.
- **O4:** the offline sync endpoint.
- **O5:** account caps.

O2 doesn't tie refresh tokens to the till they were issued on (a refresh from a different browser is still accepted while the session is active). This can be added in O3 if offline grants need it.
