# WS1-1b follow-up: `POST /auth/logout` and contract corrections

Branch `feature/ws1-1b-logout`, created from `feature/ws1-1b-device-registration` (`c8a6cd8`), because WS1-1b isn't merged into `main` yet. Not pushed or merged.

## Decisions

| # | Decision |
|---|---|
| D1 | `POST /auth/refresh` without the `refreshToken` cookie now returns **401 with an empty body**, like an invalid token. It was 400: the cookie was a required `@CookieValue`, so Spring refused the request before our code ran. |
| D2 | Refresh-token revocation added to the go-live checklist as **item 14** (Medium, Open). |
| D3 | Forced logout from another site (CSRF is off API-wide) is documented as accepted. No Origin check. |
| D4 | Logout stays under the `/auth/**` burst limit. |

## What changed

**`POST /auth/logout`** (`auth/AuthController.java`, `auth/AuthSecurityRules.java`)
- Public (`permitAll`) and idempotent. Always returns 204 with no body.
- Works without a session and with an expired or invalid access token: `JwtAuthenticationFilter` ignores such a token, and the route is public.
- Sends a single `Set-Cookie` that clears `refreshToken` (empty value, `Max-Age=0`) with the attributes of the issued cookie.
- Never touches `deviceToken` or any other cookie.

**One builder for the refresh cookie** (`auth/RefreshCookies.java`, new)
- `issue(response, token, maxAge)` and `clear(response)` share one `ResponseCookie` builder: name `refreshToken`, `Path=/auth/refresh`, no `Domain`, `HttpOnly`, `Secure` + `SameSite=None` when `cookie.secure=true` (the default), not Secure + `SameSite=Lax` when false (dev profile).
- `AuthController` no longer builds the cookie itself; the `cookie.secure` value moved into the helper. Login and PIN-login cookies are unchanged.

**`POST /auth/refresh`**: the cookie is optional at the binding; a missing or blank one throws `BadCredentialsException`, which the existing handler turns into an empty 401 (D1).

**What logout can't do.** The browser sends the refresh cookie only to `/auth/refresh`, so the server never sees it at `/auth/logout` and can't revoke it. Logout removes it from this browser only (checklist item 14).

## Docs

- `docs/2026-11-13-device-registration-api-contract.md`:
  - Section 2: credentials on every request **except `/public/**`**. `/public/**` already accepts credentialed CORS today: one CORS rule covers `/**` (the configured origins, with credentials).
  - Section 7: `/auth/refresh` 401 change, and new 7.1 `POST /auth/logout`: request, response, cookie detail, what it can't revoke, CSRF and CORS notes, burst limit.
  - Section 9 checklist: the `/public/**` exception, calling logout, and 401 from refresh.
  - New section 10, Backlog: pre-sign-in "This till: {name}" endpoint (not planned), refresh-token revocation, WS1-1c.
- `docs/2026-11-11-go-live-checklist.md`: item 14.

## Tests

New:
- `auth/RefreshCookiesTest` (unit): issue and clear share every attribute, in prod and dev settings; clear writes one empty cookie with `Max-Age=0`.
- `auth/AuthLogoutIntegrationTests`:
  - **Prod:** 204 without a session; 204 with an expired and with a garbage access token; called twice is harmless; exactly one `Set-Cookie`, matching the attributes login issues (apart from value and Max-Age); after logout, `/auth/refresh` without a cookie is 401 with no body.
  - **Dev** (`cookie.secure=false`): cleared without `Secure`, with `SameSite=Lax`, matching login.
  - **Till cookie** (device trust `required`): logout with a till cookie and a refresh cookie sends no `deviceToken` Set-Cookie, and the till still loads `/users/cashiers`.

Results: the 8 new tests pass. Full suite: **619 of 619 passed** (611 before plus the 8 new), with device trust and login protection at their test defaults (off).

## Frontend impact

- On sign-out, call `POST /auth/logout` (with credentials), then drop the access token. The till stays registered.
- `/auth/refresh` without a cookie now gives 401 instead of 400; treat 401 as signed out.
- `/public/**` may be called with or without credentials.

## Files

Created:
- `src/main/java/com/beaconculinary/api/auth/RefreshCookies.java`
- `src/test/java/com/beaconculinary/api/auth/RefreshCookiesTest.java`
- `src/test/java/com/beaconculinary/api/auth/AuthLogoutIntegrationTests.java`
- `docs/2026-11-13-ws1-1b-logout-change-report.md`

Modified:
- `src/main/java/com/beaconculinary/api/auth/AuthController.java`
- `src/main/java/com/beaconculinary/api/auth/AuthSecurityRules.java`
- `docs/2026-11-13-device-registration-api-contract.md`
- `docs/2026-11-11-go-live-checklist.md`
