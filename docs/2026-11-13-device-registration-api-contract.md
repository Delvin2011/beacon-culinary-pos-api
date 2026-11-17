# POS till registration: API contract (WS1-1b)

What the backend sends and expects once POS till registration (device trust) is in place. It builds on the login-protection contract (`docs/2026-11-11-login-protection-api-contract.md`), which still applies in full.

**The goal:**
- Only **registered POS tills** can show the POS staff list, sign cashiers and admins in by PIN, and ask for manager-PIN approval.
- Kitchen and bar screens, and PIN sign-in for kitchen, barista and head-chef staff, work from any device.
- Password sign-in works from any computer.

**Status:** this is the contract to build against. Responses marked *(required)* or *(optional)* depend on the server's `security.device-trust.mode` (section 3). With the default, `off`, every existing endpoint behaves exactly as today.

## 1. Concepts

| Term | Meaning |
|---|---|
| **Device (till)** | One physical POS machine, identified by its serial number. The server gives it a `deviceId` (UUID). Only type `POS` exists for now. |
| **Credential (browser)** | One browser on that machine. The server gives it a `credentialId` (UUID) and a secret token. One till can have several browsers if multi-browser is enabled (section 3). |
| **Device token** | A random secret (256 bits, URL-safe), held only in the `deviceToken` cookie. The server stores only its SHA-256 hash. |

`deviceId` and `credentialId` are not secret; they appear in responses and logs. The token never appears in any response body or log.

**Serial numbers are normalised** before they're stored or compared: surrounding spaces trimmed, all whitespace removed, upper-cased. So ` ab 12-c ` becomes `AB12-C`, and that's what responses show. Asset tags are only trimmed.

## 2. The `deviceToken` cookie

Set by the server when a browser is registered (section 6.2) and re-sent while the till is in use.

| Attribute | Value |
|---|---|
| Name | `deviceToken` |
| Value | The token (opaque to the frontend) |
| `HttpOnly` | yes: JavaScript can't read it |
| `Secure` | yes (not in local development, where `cookie.secure=false`) |
| `SameSite` | `None` when `Secure`; `Lax` in local development |
| `Path` | `/` (sent with every request to the API) |
| `Max-Age` | `cookie-max-age-days` (365 days by default) |

**How long it lasts:**
- A browser's registration stays valid until revoked.
- **An unused registration expires** after `idle-expiry-days` (90 days by default) without the till contacting the server. That browser must then be registered again. Lists show it as `EXPIRED` (section 6.2, Statuses).
- **The cookie renews itself:** while the till is in use, the server updates its "last seen" at most every `last-seen-interval` (5 minutes) per browser. Each time, it re-sends the same cookie with a fresh `Max-Age`. A till in regular use never loses its registration.

**Same-site versus cross-site:**
- **Production plan:** the frontend and the API on custom domains under **one registrable domain** (for example `pos.<domain>` and `api.<domain>`). The cookie is then first-party and reliable. This is a production blocker in the go-live checklist.
- **Until those domains exist** (default Azure hostnames), the frontend and API are cross-site. The cookie then behaves exactly like the existing `refreshToken` cookie (`SameSite=None; Secure`): it works in Chrome and Edge with their default settings, but not in Incognito/InPrivate or where third-party cookies are blocked.
- **Till browsers:** Chromium (Chrome or Edge) in kiosk mode, normal (not Incognito) profile.

**Frontend requirement:** every request to the API must include credentials (`fetch(..., { credentials: "include" })`, or `withCredentials: true`), **except `/public/**`**. Otherwise the cookie isn't sent and gated requests are refused. Previously only `/auth/refresh` needed the cookie.

**`/public/**` (the public board, `/public/board/**`)** runs on display screens and doesn't need the till cookie, so it may be called with or without credentials. Today one CORS rule covers every path (`auth/SecurityConfig.java`, registered for `/**`): the origins in `CORS_ALLOWED_ORIGINS`, with credentials allowed. So `/public/**` already accepts credentialed requests, but only from those origins, as every other path does.

## 3. Settings and modes

All settings are under `security.device-trust`, are validated at startup, and can be overridden by environment variable.

| Setting | Default | Environment variable | Meaning |
|---|---|---|---|
| `mode` | `off` | `SECURITY_DEVICETRUST_MODE` | `off`, `optional` or `required` (below) |
| `multi-browser-enabled` | `false` | `SECURITY_DEVICETRUST_MULTIBROWSERENABLED` | Allow more than one browser per till |
| `require-serial` | `true` | `SECURITY_DEVICETRUST_REQUIRESERIAL` | Serial number required when registering |
| `idle-expiry-days` | `90` | `SECURITY_DEVICETRUST_IDLEEXPIRYDAYS` | Unused browsers expire after this |
| `cookie-max-age-days` | `365` | `SECURITY_DEVICETRUST_COOKIEMAXAGEDAYS` | Cookie lifetime, renewed while in use |
| `last-seen-interval` | `5m` | `SECURITY_DEVICETRUST_LASTSEENINTERVAL` | At most one last-seen update and cookie renewal per browser per interval |
| `reauth-max-age` | `30m` | `SECURITY_DEVICETRUST_REAUTHMAXAGE` | How recent the admin's password sign-in must be to register, edit or revoke |
| `device-pin.max-failures` / `.window` | `30` / `15m` | `SECURITY_DEVICETRUST_DEVICEPIN_MAXFAILURES` / `_WINDOW` | The DEVICE_PIN rule (section 8) |

`multi-browser-enabled=true` requires `require-serial=true`, otherwise the app doesn't start.

### 3.1 Modes

| | `off` (default) | `optional` | `required` |
|---|---|---|---|
| Gated endpoints (section 4) | Work as today | Work as today | **403 `DEVICE_NOT_REGISTERED`** without a valid browser registration |
| Valid registration | Last seen and cookie renewal only | Recognised: recorded in sign-in attempts, used for per-till limits, `dev` claim in PIN-login tokens | Same as `optional` |
| Missing registration | Ignored | Allowed; logged | Refused on gated endpoints |
| Invalid, expired or revoked registration | Ignored | Allowed; logged with an alert | Refused on gated endpoints, with an alert |
| Admins on the KITCHEN and BAR login tiles | Shown | Shown | **Hidden** (admin PIN sign-in needs a registered till) |
| Device administration endpoints (section 6) | Available (tills can be registered in advance) | Available | Available |

In `off` mode the server still recognises a valid cookie so that its last-seen time and cookie stay fresh. This means tills registered in advance don't expire before the mode is switched on. Nothing is checked or refused.

### 3.2 Multi-browser and serial settings

| `require-serial` | `multi-browser-enabled` | Registering a serial that belongs to an active till… |
|---|---|---|
| `true` | `false` (default) | …that has an active browser: **409 `DEVICE_SERIAL_IN_USE`**. Revoke that browser (or the till) first. …with no active browser: the new browser joins **the same till**. |
| `true` | `true` | …that has an active browser: **409 `DEVICE_SERIAL_EXISTS`** with `details.deviceId`. Call again with `joinDeviceId` to add this browser to that till. …with no active browser: joins the same till directly. |
| `false` | `false` | Same as the first row when a serial is given. Without a serial, every registration creates a new till. |
| `false` | `true` | Not allowed: the app doesn't start. |

**Switching multi-browser from `true` to `false`:** browsers already added keep working (nothing is revoked automatically). New joins are refused with `DEVICE_JOIN_DISABLED`, and a till with an active browser gets `DEVICE_SERIAL_IN_USE` again.

## 4. Which requests are gated

The server decides from the endpoint and the target account. Nothing the client sends (such as `screen`) can bypass it.

| Request | Gated when | In `required` mode without a valid registration |
|---|---|---|
| `GET /users/cashiers` | always | 403 `DEVICE_NOT_REGISTERED` |
| `GET /users/login-tiles?screen=POS` | `screen=POS` | 403 `DEVICE_NOT_REGISTERED` |
| `GET /users/login-tiles?screen=KITCHEN` or `BAR` | never | 200; admins left out of the list |
| `POST /admin/authorize`, `POST /admin/authorize-session` | always | 403 `DEVICE_NOT_REGISTERED` |
| `POST /auth/pin-login` | the account is CASHIER or ADMIN, **or the cashier ID is unknown** | 403 `DEVICE_NOT_REGISTERED` |
| `POST /auth/pin-login` for KITCHEN, BARISTA or HEAD_CHEF | never | as today |
| Password sign-in, refresh, kitchen, bar, back office, staff management, `/orders/**`, `/shifts/**` | never | as today |

**An unknown cashier ID gets the same 403** as a cashier or admin, so the response never reveals whether an ID exists.

**Order of checks:**
1. The burst limit (429 `RATE_LIMITED`).
2. For requests gated for everyone (cashier list, POS tiles, manager-PIN approval): the device check, refusing with 403 immediately.
3. For PIN sign-in only, in this order:
   1. the Phase 2 lock/block check (429)
   2. the account's role
   3. the device check (403)
   4. the PIN check (401 or 200)

A device refusal **never checks the PIN** and is **never counted as a failed sign-in** for lockout rules. It's recorded with outcome `DEVICE_NOT_REGISTERED`.

**Orders and shifts** (`/orders/**`, `/shifts/**`) aren't gated in this phase. In `optional` and `required` mode, a cashier session using them from a browser without a valid registration is logged (marker `DEVICE_UNBOUND_TILL_REQUEST`, at most once per user per 5 minutes) so it can be counted. Tying sessions to a till is planned for WS1-1c.

## 5. Error shapes

Two shapes exist. **Always branch on `code`**, never on the text fields, which may change.

| Shape | Fields | Used for |
|---|---|---|
| `ErrorDto` | `error` (text), `code`, `details` (object, **only when there is extra data**) | Every device error (403, 409), and existing errors such as `SELF_UNLOCK` |
| `LoginProtectionErrorDto` | `code`, `message`, `retryAfterSeconds` | Every 429 (login protection and the burst limit), unchanged |
| Field map | `{ "<field>": "<message>" }` | 400 validation errors, as elsewhere |

### Error codes

| Status | `code` | Shape | When | `details` |
|---|---|---|---|---|
| 403 | `DEVICE_NOT_REGISTERED` | ErrorDto | Gated request in `required` mode without a valid registration (missing, invalid, expired and revoked look the same) | none |
| 403 | `REAUTH_REQUIRED` | ErrorDto | Register, edit or revoke when the admin didn't sign in **with a password** within `reauth-max-age` (30 min) | none |
| 409 | `DEVICE_SERIAL_IN_USE` | ErrorDto | Serial belongs to an active till that has an active browser (multi-browser off, or an edit) | `deviceId`, `deviceName` |
| 409 | `DEVICE_SERIAL_EXISTS` | ErrorDto | Same, with multi-browser on: confirm with `joinDeviceId` | `deviceId`, `deviceName` |
| 409 | `DEVICE_JOIN_MISMATCH` | ErrorDto | `joinDeviceId` isn't the active till for that serial | none |
| 409 | `DEVICE_JOIN_DISABLED` | ErrorDto | `joinDeviceId` sent while multi-browser is off | none |
| 409 | `DEVICE_ASSET_TAG_IN_USE` | ErrorDto | Asset tag belongs to another active till | `deviceId`, `deviceName` |
| 409 | `DEVICE_ALREADY_REGISTERED` | ErrorDto | This browser already holds a valid registration | `deviceId`, `deviceName`, `credentialId` |
| 409 | `DEVICE_REVOKED` | ErrorDto | Edit of a revoked till or browser | none |
| 429 | `RATE_LIMITED` | LoginProtectionErrorDto | Burst limit, or the DEVICE_PIN rule (section 8) | none |

## 6. Device administration (ADMIN)

All under `/admin/devices`, ADMIN only (401 without sign-in, 403 for other roles, as for all `/admin/**`). Unknown IDs return **404** with an empty body.

### 6.1 `GET /admin/devices/config`

The settings the frontend needs. Available to any ADMIN session.

```json
{"mode": "optional", "multiBrowserEnabled": false, "requireSerial": true}
```

### 6.2 `POST /admin/devices`: register this browser

**Must be called from the browser being registered.** The cookie is set on that browser. Requires a password sign-in within the last 30 minutes (else 403 `REAUTH_REQUIRED`).

**Request:**

```json
{"name": "Till 1", "serialNumber": "pf3x 9k2b", "assetTag": "BC-0042", "joinDeviceId": null}
```

| Field | Rules |
|---|---|
| `name` | required, 1–100 characters |
| `serialNumber` | required when `requireSerial`; at most 100 characters after normalisation |
| `assetTag` | optional, at most 100 characters; unique among active tills |
| `joinDeviceId` | only with multi-browser on, after a 409 `DEVICE_SERIAL_EXISTS` |

**Outcomes:**

| Situation | Response |
|---|---|
| New serial | **201** with the new till (6.3 shape) and its first browser; `Set-Cookie: deviceToken=…` |
| Serial of an active till with **no active browser** | **201**: this browser joins **that** till (same `deviceId`); the till's name and asset tag are kept (change them with 6.4) |
| Serial of an active till with an active browser, multi-browser **off** | 409 `DEVICE_SERIAL_IN_USE` |
| Same, multi-browser **on**, no `joinDeviceId` | 409 `DEVICE_SERIAL_EXISTS` with `details.deviceId` |
| Same, multi-browser on, `joinDeviceId` = that till | **201**: this browser joins that till |
| `joinDeviceId` not matching the serial's active till | 409 `DEVICE_JOIN_MISMATCH` |
| `joinDeviceId` with multi-browser off | 409 `DEVICE_JOIN_DISABLED` |
| Asset tag of another active till | 409 `DEVICE_ASSET_TAG_IN_USE` |
| This browser already registered | 409 `DEVICE_ALREADY_REGISTERED` (revoke this browser first) |
| Field errors | 400 field map |

**Example: 201 Created**

```http
HTTP/1.1 201 Created
Set-Cookie: deviceToken=<opaque>; Path=/; Max-Age=31536000; Secure; HttpOnly; SameSite=None
Content-Type: application/json
```
```json
{
  "id": "6f1c2a0e-3d5b-4b8e-9a51-1f2c3d4e5f60",
  "name": "Till 1",
  "type": "POS",
  "serialNumber": "PF3X9K2B",
  "assetTag": "BC-0042",
  "status": "ACTIVE",
  "registeredBy": "Canteen Admin",
  "registeredAt": "2026-11-13T09:00:00Z",
  "revokedAt": null,
  "revokeReason": null,
  "thisDevice": true,
  "credentials": [
    {
      "id": "b2d4f6a8-1c3e-4a5b-8d7f-9e0a1b2c3d4e",
      "browserLabel": "Edge on Windows",
      "status": "ACTIVE",
      "createdBy": "Canteen Admin",
      "createdAt": "2026-11-13T09:00:00Z",
      "lastSeenAt": "2026-11-13T09:00:00Z",
      "lastSeenIp": "203.0.113.7",
      "revokedAt": null,
      "thisBrowser": true
    }
  ]
}
```

`browserLabel` is suggested by the server from the browser's User-Agent; admins can change it (6.5).

**Statuses.** A till's `status` is `ACTIVE` or `REVOKED`. A browser's `status` is:
- `ACTIVE`: in use within `idle-expiry-days`.
- `EXPIRED`: not revoked, but unused for `idle-expiry-days`. Its cookie no longer works (`DEVICE_NOT_REGISTERED`). It counts as **not active**, so registering the till's serial again from any browser attaches to the same till (table above). Show it as "Expired: register this browser again".
- `REVOKED`: revoked by an admin.

The response also carries `revokedBy` (the admin's name) on tills and browsers, next to `revokedAt` and `revokeReason`.

**Example: 409 serial exists (multi-browser on)**

```json
{"error": "A till with this serial number is already registered. Confirm to add this browser to it.", "code": "DEVICE_SERIAL_EXISTS", "details": {"deviceId": "6f1c2a0e-3d5b-4b8e-9a51-1f2c3d4e5f60", "deviceName": "Till 1"}}
```

**Example: 409 serial in use (multi-browser off)**

```json
{"error": "This serial number belongs to an active till. Revoke its browser first.", "code": "DEVICE_SERIAL_IN_USE", "details": {"deviceId": "6f1c2a0e-3d5b-4b8e-9a51-1f2c3d4e5f60", "deviceName": "Till 1"}}
```

**Example: 409 already registered**

```json
{"error": "This browser is already registered to a till. Revoke it first.", "code": "DEVICE_ALREADY_REGISTERED", "details": {"deviceId": "6f1c2a0e-3d5b-4b8e-9a51-1f2c3d4e5f60", "deviceName": "Till 1", "credentialId": "b2d4f6a8-1c3e-4a5b-8d7f-9e0a1b2c3d4e"}}
```

**Example: 403 re-authentication needed**

```json
{"error": "Sign in again with your password to manage tills.", "code": "REAUTH_REQUIRED"}
```

### 6.3 `GET /admin/devices`

All tills (active and revoked), newest first, each with its browsers, in the 6.2 shape.
- `thisDevice` and `thisBrowser` mark the till and browser making the request.
- `registeredBy` and `createdBy` (and `revokedBy` when revoked) are names saved at the time of the action, so they stay correct if the user is later deleted.
- Revoked tills and browsers also have `revokedBy`, `revokedAt` and `revokeReason`.

### 6.4 `PUT /admin/devices/{id}`: edit a till

Requires the recent password sign-in (else 403 `REAUTH_REQUIRED`).

```json
{"name": "Till 1 (front)", "serialNumber": "PF3X9K2B", "assetTag": "BC-0042"}
```

200 with the till. The same rules and conflicts apply as for 6.2: `DEVICE_SERIAL_IN_USE` and `DEVICE_ASSET_TAG_IN_USE` (against other active tills), or 409 `DEVICE_REVOKED` for a revoked till.

### 6.5 `PUT /admin/devices/{id}/credentials/{credentialId}`: rename a browser

Requires the recent password sign-in.

```json
{"browserLabel": "Edge (kiosk)"}
```

200 with the till; 409 `DEVICE_REVOKED` for a revoked browser.

### 6.6 `POST /admin/devices/{id}/revoke`: retire a till

Requires the recent password sign-in. Body optional: `{"reason": "Replaced"}` (at most 255 characters).
- The till **and all its browsers** are revoked; they stop being recognised at once.
- The serial and asset tag become free to register again (as a new till).
- 200 with the till. Revoking an already revoked till returns 200 and changes nothing.

### 6.7 `POST /admin/devices/{id}/credentials/{credentialId}/revoke`: revoke one browser

Requires the recent password sign-in. Body optional: `{"reason": "Browser reinstalled"}`.
- 200 with the till. Revoking an already revoked browser returns 200 and changes nothing.
- **If it was the till's last active browser,** the till **stays active with no browsers.** Registering its serial again (6.2) attaches the new browser to the **same** till.
- Only 6.6 revokes a till.

## 7. Changes to existing endpoints

| Endpoint | Change |
|---|---|
| `POST /auth/pin-login` | 403 `DEVICE_NOT_REGISTERED` *(required)* for CASHIER and ADMIN accounts and unknown IDs. Other responses unchanged. |
| `GET /users/cashiers` | 403 `DEVICE_NOT_REGISTERED` *(required)* |
| `GET /users/login-tiles` | `screen=POS`: 403 `DEVICE_NOT_REGISTERED` *(required)*. `KITCHEN` and `BAR`: unchanged, but admins are left out *(required)*. |
| `POST /admin/authorize`, `/admin/authorize-session` | 403 `DEVICE_NOT_REGISTERED` *(required)*, checked before the PIN |
| `POST /auth/refresh` | Without the `refreshToken` cookie: **401 with an empty body** (previously 400), the same as an invalid or expired refresh token |
| `POST /auth/logout` | **New**, see 7.1 |
| Access tokens (all sign-ins) | New claims (below); existing claims unchanged |

### 7.1 `POST /auth/logout`

Removes the refresh cookie from this browser. In every device-trust mode.

**Request:** no body, no `Authorization` needed. Send credentials as for every request. Public (`permitAll`), so it works without a session and with an expired access token.

**Response:** always **204** with no body. Idempotent: calling it again is harmless.

```http
HTTP/1.1 204 No Content
Set-Cookie: refreshToken=; Path=/auth/refresh; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure; HttpOnly; SameSite=None
```

**The cookie:**
- The `Set-Cookie` has exactly the attributes of the issued cookie (same builder, `auth/RefreshCookies.java`): name `refreshToken`, `Path=/auth/refresh`, no `Domain`, `HttpOnly`, `Secure` with `SameSite=None`. In local development (`cookie.secure=false`) it is not `Secure` and `SameSite=Lax`, as when issued.
- The value is empty and `Max-Age=0`, so the browser deletes it.
- **The `deviceToken` cookie is not touched**, nor any other cookie: the till stays registered after logout.

**What it can't do:** the browser sends the refresh cookie only to its path, `/auth/refresh`, so it **isn't sent to `/auth/logout`**. The server therefore can't revoke that refresh token; logout only removes it from this browser. A copied refresh token stays valid until it expires (7 days). Server-side revocation is go-live checklist item 14.

**The frontend should:** call it, then drop the access token from memory and go to the sign-in screen. Don't wait on it or retry it: a failed call (network error, 429) only means the cookie may remain until it expires.

**CSRF and CORS:** the same as `/auth/refresh`.
- CSRF protection is off for the whole API (stateless bearer tokens). Another site could therefore submit a form to `/auth/logout` and sign a user out of this browser. Nothing is exposed and no data changes; this is accepted.
- CORS: the configured origins, with credentials, as for every path.
- It is under the per-address burst limit for `/auth/**` (429 `RATE_LIMITED`), like every other `/auth/` request.

**New claims in access tokens:**

| Claim | Value |
|---|---|
| `amr` | `"pwd"` (password sign-in) or `"pin"` (PIN sign-in) |
| `auth_time` | When the person originally signed in (seconds since 1970, UTC) |
| `dev` | The till's `deviceId`, on tokens from PIN sign-in on a registered till *(optional, required)* |

All three are kept when the token is refreshed. **Refresh isn't tied to the till yet** (WS1-1c). The frontend doesn't need to read these claims. Tokens issued before this release have no `amr`, so managing tills needs a fresh password sign-in.

## 8. Sign-in protection on a till

These add to the Phase 2 rules when a valid registration is present *(optional, required)*. Each works in login protection's `monitor` and `enforce` modes as described in Phase 2.

| Rule | Counted per | Threshold | Effect (enforce) |
|---|---|---|---|
| `DEVICE_PIN` (new) | Till: all accounts, all browsers of that machine | 30 failed PIN sign-ins in 15 min | 429 `RATE_LIMITED` with `Retry-After` |
| `PIN_PAIR` (changed key) | Cashier + **till** (instead of cashier + network address when a till is known) | 5 in 15 min | 429 `RATE_LIMITED` |

Sign-in attempts record the till (`deviceId`) and browser (`credentialId`) when known.

## 9. Frontend checklist

- [ ] Send credentials on **every** API request except `/public/**` (section 2).
- [ ] On sign-out, call `POST /auth/logout` (7.1), then drop the access token. The till stays registered.
- [ ] Treat 401 from `/auth/refresh` as signed out, including when there is no refresh cookie (previously 400).
- [ ] Handle 403 `DEVICE_NOT_REGISTERED` on the POS login screen, the cashier tiles and manager-PIN approval: show "This till isn't registered. Ask a manager." Never retry automatically.
- [ ] Read `GET /admin/devices/config` to adapt the device screens (mode, multi-browser, serial required).
- [ ] Device registration screen, used **on the till itself**:
  - serial number (show the normalised value back)
  - name and asset tag
  - handle `DEVICE_SERIAL_IN_USE`, `DEVICE_SERIAL_EXISTS` (offer "Add this browser to Till 1?", then resend with `joinDeviceId`), `DEVICE_ASSET_TAG_IN_USE`, `DEVICE_ALREADY_REGISTERED` and `REAUTH_REQUIRED` (send the admin to password sign-in)
- [ ] Device list:
  - tills with their browsers; mark "this till" and "this browser"
  - revoke a till or one browser (with a confirmation and an optional reason)
  - rename
  - show the last-seen time in local time
- [ ] Branch on `code` everywhere; show `error` (ErrorDto) or `message` (429).
- [ ] Kitchen and bar screens need no change; admins may disappear from their tiles in `required` mode.

## 10. Backlog

Not planned now; listed so they are not forgotten.

- **This till's own name before sign-in** ("This till: {name}" on the POS login screen). **Delivered in O2:** `GET /pos/till-status` (public) returns this till's code and name; see `docs/2026-11-17-o2-tills-shifts-api-contract.md`, section 7.1. Original note: today only `GET /admin/devices` (ADMIN) returns `thisDevice`. An optional public endpoint could return `{deviceId, name}` for the browser's own valid registration, and 204 or 404 otherwise. Decide whether showing the name before sign-in is acceptable.
- **Refresh-token revocation** on logout or on revoking a till: see go-live checklist item 14.
- **Tying cashier sessions to a till** (`/orders/**`, `/shifts/**`): WS1-1c, see section 4. **Delivered in O2:** per-till shifts and server-side sessions (O2 contract, sections 4 and 8.5).
