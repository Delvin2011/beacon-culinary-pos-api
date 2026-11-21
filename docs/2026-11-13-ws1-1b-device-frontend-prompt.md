# WS1-1b frontend prompt: POS till registration

## ROLE AND RULES

You are adding POS till registration (WS1-1b, Phase 3) to the canteen POS frontend. The Phase 2 frontend work is complete: 429 handling, countdowns, staff unlock and 6-digit PINs.

Working rules (mandatory):
1. Step 1 is a READ-ONLY analysis. Change nothing until I approve your plan.
2. Show the diff before modifying any existing file, and wait for confirmation.
3. Work on branch `feature/ws1-1b-device-frontend`. Commit per step; do not push or merge.
4. Name new docs `YYYY-MM-DD-descriptive-name.md`. Summarise after each step, and list every file created or modified at the end.
5. Build against the backend contract `docs/2026-11-13-device-registration-api-contract.md`, which I will give you. It is authoritative. If something you need isn't in it, list it as a question; do not guess.
6. Branch on the error `code` only, never on message text. Device errors are `ErrorDto` `{error, code, details?}`: show `error`. 429s stay `{code, message, retryAfterSeconds}`.

## BACKEND BEHAVIOUR (summary; the contract is authoritative)

**Scope**
- Only the POS screen needs a registered till. Kitchen and bar screens work from any device.
- Password sign-in works from any computer.
- Kitchen, barista and head-chef PIN sign-in works anywhere.

**Tills, browsers and the cookie**
- A till is one machine, identified by its serial number.
- Each browser on it gets its own credential, held in the HttpOnly cookie `deviceToken` set by the backend. The frontend never sees or stores the token.
- **Every** API request must include credentials (`withCredentials: true` or `credentials: 'include'`), not only the gated ones, **except `/public/**`**. The backend renews the cookie and updates "last seen" on ordinary requests. Before this change only `/auth/refresh` needed the cookie.
- `/public/**` (the public board on display screens) doesn't need the till cookie. It accepts requests with or without credentials (from the configured origins), so either is fine there.
- Serial numbers are normalised by the server: all whitespace removed, upper-cased (` ab 12-c ` becomes `AB12-C`). Show the value from the response, not what was typed.

**Modes.** `GET /admin/devices/config` returns `{mode, multiBrowserEnabled, requireSerial}`, where `mode` is `off`, `optional` or `required`. The UI must work in all three:
- `off` (the default) and `optional`: nothing is refused. Tills can still be registered in advance.
- `required`: the gated requests below return 403 `DEVICE_NOT_REGISTERED` without a valid registration.

**Admin endpoints.** All are ADMIN only. Unknown IDs return 404 with an empty body.

| Request | Purpose |
|---|---|
| `GET /admin/devices/config` | Mode and settings |
| `POST /admin/devices` `{name, serialNumber, assetTag?, joinDeviceId?}` | Register **the browser making the request**. Returns 201 with the till. |
| `GET /admin/devices` | All tills (active and revoked), newest first, each with its browsers and the `thisDevice` / `thisBrowser` flags |
| `PUT /admin/devices/{id}` `{name, serialNumber, assetTag}` | Edit a till. This is a **full replace**: always send all three fields; an omitted `assetTag` clears it. |
| `PUT /admin/devices/{id}/credentials/{credentialId}` `{browserLabel}` | Rename a browser |
| `POST /admin/devices/{id}/revoke` `{reason?}` | Revoke the whole till and all its browsers |
| `POST /admin/devices/{id}/credentials/{credentialId}/revoke` `{reason?}` | Revoke one browser |

`reason` is optional, at most 255 characters. Revoking something already revoked returns 200 and changes nothing. All write calls return the till.

**Re-authentication.** Register, edit, rename and revoke need an admin session from a **password** sign-in within the last 30 minutes. Otherwise they return 403 `REAUTH_REQUIRED`. This applies:
- to an admin who signed in by PIN;
- to sessions older than 30 minutes, even after token refresh;
- to sessions from before this release.

On `REAUTH_REQUIRED`, send the admin to password sign-in, then bring them back to the same screen with the form intact. `GET` requests (list and config) don't need it.

**Statuses**
- A till is `ACTIVE` or `REVOKED`.
- A browser is `ACTIVE`, `EXPIRED` (unused for 90 days; its cookie no longer works; show "Expired: register this browser again") or `REVOKED`.
- Revoked items carry `revokedBy`, `revokedAt` and `revokeReason`.
- Timestamps are UTC ISO-8601; show them in local time.

**Registration outcomes (`POST /admin/devices`)**

| Response | Meaning |
|---|---|
| 201 | Registered. If the serial belonged to an active till with **no active browser** (for example its browser was revoked or expired), this browser joins **that** till: the response has the existing `id`, `name` and asset tag, and the name typed in the form is **not** applied. Always build the success message from the response. |
| 409 `DEVICE_SERIAL_IN_USE` | Multi-browser off; the serial belongs to an active till with an active browser. `details: {deviceId, deviceName}`. Revoke that till's browser, or the till, first. |
| 409 `DEVICE_SERIAL_EXISTS` | Multi-browser on; same situation. `details: {deviceId, deviceName}`. Ask for confirmation, then resend the same form with `joinDeviceId = details.deviceId`. |
| 409 `DEVICE_JOIN_MISMATCH` | `joinDeviceId` doesn't match the serial's till; for example the serial was changed after the confirmation. Restart the flow. |
| 409 `DEVICE_JOIN_DISABLED` | `joinDeviceId` sent while multi-browser is off; for example the setting changed. Reload the config. |
| 409 `DEVICE_ASSET_TAG_IN_USE` | `details: {deviceId, deviceName}` |
| 409 `DEVICE_ALREADY_REGISTERED` | This browser already holds a valid registration. `details: {deviceId, deviceName, credentialId}`. |
| 403 `REAUTH_REQUIRED` | See above |
| 400 field map | `{"name": "...", "serialNumber": "...", "assetTag": "..."}`. Show the messages next to the fields. |

Edit (`PUT`) can return 409 `DEVICE_SERIAL_IN_USE`, 409 `DEVICE_ASSET_TAG_IN_USE`, 409 `DEVICE_REVOKED` (the till or browser is revoked), 403 `REAUTH_REQUIRED` or 400.

**Gated requests** (`required` mode, without a valid registration: 403 `DEVICE_NOT_REGISTERED`). A missing, invalid, expired or revoked registration all look the same.
- `GET /users/cashiers`
- `GET /users/login-tiles?screen=POS`
- `POST /auth/pin-login` for CASHIER and ADMIN accounts **and for unknown IDs**. The response never reveals which.
- `POST /admin/authorize` and `POST /admin/authorize-session` (manager-PIN approval). Checked before the PIN.

**Not gated**
- `GET /users/login-tiles?screen=KITCHEN|BAR`.
  - In `required` mode, admins are **left out** of these lists.
  - The KITCHEN list never contained admins; the BAR list does in `off` and `optional` mode.
  - Tiles carry only `{id, name}` (no role), so the frontend can't tell an admin tile from another.
- Password sign-in, refresh, `/orders/**`, `/shifts/**` and everything else.

**Order of checks on PIN sign-in:**
1. The burst limit or a lock (429)
2. The device check (403)
3. The PIN (401 or 200)

A device refusal never checks the PIN and never counts as a failed sign-in.

**Sign-out: `POST /auth/logout`** (contract section 7.1)
- No body, and no `Authorization` header needed. Send it with credentials, like every request.
- It is public, so it works with an expired access token or no session.
- It always returns **204** with no body, and calling it again is harmless.
- It deletes the `refreshToken` cookie from this browser.
- It **never touches the `deviceToken` cookie**: the till stays registered after sign-out.
- The browser never sends the refresh cookie to `/auth/logout`, so the server can't revoke that token. Logout only removes it from this browser.
- On sign-out, call it, then drop the access token from memory and go to the sign-in screen.
- Don't block on it or retry it. If the call fails (network error, 429), the cookie only stays until it expires.

**`POST /auth/refresh` without the refresh cookie** now returns **401 with an empty body**. It used to return 400. Treat any 401 from refresh as signed out.

**New 429 on PIN sign-in:** `RATE_LIMITED` with `Retry-After` after 30 failed PIN sign-ins on one till in 15 minutes (rule `DEVICE_PIN`). Existing 429 handling already covers it.

**Cross-site cookies.** Until the frontend and API share one registrable domain (a go-live item), the cookie behaves like `refreshToken` (`SameSite=None; Secure`):
- It works in normal Chrome and Edge profiles.
- It does **not** work in Incognito/InPrivate or where third-party cookies are blocked.

There, registration returns 201 but the browser keeps no cookie. Detect this; see Step 2.

## STEP 1: ANALYSE (read-only) AND REPORT

1. **Cookies:** how requests send cookies today (`withCredentials` / `credentials`), per HTTP client or instance, including `fetch` calls outside the main client.
2. **POS login data:** where the POS login tiles, the cashier list and the PIN pad load, and what happens when those requests fail.
3. **Admin pages:**
   - where they live, and how routing and admin-only guards work
   - where a "Registered tills" page and a "Register this till" action fit
   - how an admin's sign-in method (password or PIN) is known today, if at all
4. **Error handling:**
   - how 401, 403, 404 and 409 are handled today
   - anything that treats 403 as a logout, redirects, or retries (interceptors, the refresh logic)
   - how manager-PIN approval handles errors
5. **Screens:** how the app knows it is the POS screen versus the kitchen or bar screen.
6. **Admins on tiles:**
   - whether admins appear on the bar tiles today
   - what happens if a tile list is stale (loaded before an admin was hidden) and a tile is tapped
7. **Return after sign-in:** whether the app can send a user to password sign-in and return them to the page they came from.
8. **Sign-out and refresh:**
   - how sign-out works today, on every screen (POS, kitchen, bar, back office), including automatic sign-outs such as after a cashier's sale or after idle time
   - anything that clears cookies or storage on sign-out (it must not try to clear the till cookie)
   - how a failed `/auth/refresh` is handled, and whether anything depends on it returning 400

## STEP 2: PLAN (after I approve the analysis)

**Credentials**
- Every API request includes credentials, except `/public/**`, where either way is fine.
- Cover this with a test per HTTP client.

**Sign-out**
- Every sign-out path calls `POST /auth/logout`, with credentials, then drops the access token and returns to the sign-in screen.
- Don't wait on the call, and don't retry it.
- Nothing on sign-out clears or overwrites the till cookie; the frontend can't touch an HttpOnly cookie anyway.
- Treat 401 from `/auth/refresh`, including when there is no refresh cookie, as signed out.
- Remove any handling that relied on the old 400.

**"Register this till" screen** (admin only, on the till itself)
- Read `GET /admin/devices/config` first.
- If `mode` is `off` or `optional`, say so: "Registration isn't enforced yet; you can register tills in advance."
- Fields:
  - name (required, up to 100 characters)
  - serial number: required when `requireSerial`, up to 100 characters. Add a hint for where to find it on the machine.
  - asset tag: optional, up to 100 characters
- Explain that this registers **this browser on this machine**, and should be done from the till's kiosk browser, not a back-office PC.
- If the list shows this browser is already registered (`thisBrowser`), show "This browser is registered as {name}" instead of the form, with a link to Registered tills.
- **After 201:** confirm the cookie stuck by calling `GET /admin/devices` and checking that one browser has `thisBrowser: true`.
  - If yes: "This browser is now registered as {response.name}".
  - If yes and it joined an existing till (the response `id` was already in the list, or there are several browsers): "This browser has been added to {response.name}".
  - If no: "This browser didn't keep the till cookie. Use a normal (not private) Chrome or Edge window and allow cookies for this site, then register again." The server has created the registration anyway; offer to revoke that browser credential (its `id` is in the 201 response).
- **Outcomes, by `code`:**
  - `DEVICE_SERIAL_IN_USE`: "Serial {serial} is already registered as {details.deviceName}. Revoke its browser (or the till) in Registered tills first." Link to that till.
  - `DEVICE_SERIAL_EXISTS`: a confirmation dialog: "Serial {serial} is already registered as {details.deviceName}. Add this browser to it?"
    - On confirm, resend with `joinDeviceId = details.deviceId`.
    - On success, show "This browser has been added to {response.name}".
  - `DEVICE_JOIN_MISMATCH` and `DEVICE_JOIN_DISABLED`: show `error`, reload the config, and restart the flow.
  - `DEVICE_ASSET_TAG_IN_USE`: "Asset tag {tag} is already used by {details.deviceName}."
  - `DEVICE_ALREADY_REGISTERED`: "This browser is already registered as {details.deviceName}. To move it to another till, revoke this browser first."
  - `REAUTH_REQUIRED`: "Sign in again with your password to manage tills." Send the admin to password sign-in, then return with the form values kept.
  - 400: field messages.
  - In every message, `{serial}` is the normalised value.

**POS screen, unregistered browser**
- On `DEVICE_NOT_REGISTERED` from the tiles, the cashier list, the PIN pad or manager-PIN approval, show "This till is not registered. Ask a manager to register it." with a button to the admin **password** sign-in.
- Do not treat it as a logout.
- Do not retry automatically, including through any interceptor or refresh logic.
- A manual "Try again" button is fine.
- For manager-PIN approval, show the message in the approval dialog. The cashier stays signed in.

**Kitchen and bar screens**
- No behaviour change for staff.
- In `required` mode admins are left out of the bar list.
- If a PIN sign-in on these screens returns `DEVICE_NOT_REGISTERED` (a stale admin tile; the frontend can't know the role), show "Managers sign in with their password on this screen." Offer the password sign-in, and reload the tiles.
- No retry loop.

**"Registered tills" admin page**
- Show the mode, multi-browser and serial settings from config, read-only.
- For each till:
  - name, serial, asset tag, status
  - registered by and when
  - if revoked: by whom, when, and why
- For each of its browsers:
  - label and status (`ACTIVE`, `EXPIRED` or `REVOKED`)
  - added by and when
  - last seen (local time) and last-seen IP
  - if revoked: by whom, when, and why
- Markers: "This till" (`thisDevice`) and "This browser" (`thisBrowser`).
- Active tills first, then revoked ones (collapsed).
- **Actions:**
  - Edit a till. This is a full replace: pre-fill and send all three fields.
  - Rename a browser.
  - Revoke one browser.
  - Revoke the whole till.
- **Revoking:**
  - Confirm first, with an optional reason (up to 255 characters).
  - Warn when revoking "This browser" or "This till": in `required` mode this POS stops working for PIN sign-in at once.
  - Explain that revoking a till's last browser leaves the till registered with no browsers: registering its serial again re-attaches to the same till.
  - Revoking a till frees its serial and asset tag for a new till.
- Hide the actions on revoked items. Handle `REAUTH_REQUIRED`, `DEVICE_REVOKED` (reload the list) and 404 (reload the list).
- Show an empty state when there are no tills.

**"This till: {name}" indicator**
- The API offers no public, pre-sign-in way to read the current till's name. `GET /admin/devices` is ADMIN only.
- Don't build it. List it as a question if it's needed, and the backend can add an endpoint.
- On the admin pages, `thisDevice` is enough.

**Tests**
- Credentials are included on every request (each HTTP client).
- The form is config-driven: serial required or optional, and the mode notice.
- **Registration:**
  - success
  - joined an existing till (the success message uses the response name)
  - the cookie didn't stick
  - `DEVICE_SERIAL_IN_USE`
  - `DEVICE_SERIAL_EXISTS` confirm-and-join
  - `DEVICE_ASSET_TAG_IN_USE`
  - `DEVICE_ALREADY_REGISTERED`
  - `DEVICE_JOIN_MISMATCH`
  - `REAUTH_REQUIRED`: redirect and return with the form kept
  - 400 field errors
- **`DEVICE_NOT_REGISTERED`:**
  - On the POS tiles, cashier list and PIN pad: the message and the password-sign-in button; no logout.
  - On manager-PIN approval: the message in the dialog; the cashier stays signed in.
  - Kitchen and bar are unaffected; the bar shows the admin message on a refused PIN sign-in.
- No automatic retry or loop after a 403.
- Both pages are admin-only.
- **Registered tills page:**
  - revoke one browser vs the whole till, with confirmations and the "this browser / this till" warnings
  - edit sends all three fields
  - `EXPIRED` status shown
  - revoked items show who, when and why
- The 429 `RATE_LIMITED` on PIN sign-in still shows the existing countdown.
- **Sign-out:**
  - each sign-out path calls `POST /auth/logout` with credentials, then drops the token
  - a failed logout call still signs the user out locally, with no retry
  - after sign-out, the POS still works as a registered till: the tiles load on a registered till in `required` mode
- 401 from `/auth/refresh` signs the user out, with no loop.

## FIRST REPLY

The Step 1 analysis only. Do not change anything yet.
