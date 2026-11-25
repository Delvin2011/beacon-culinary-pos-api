# T1-5 customer-bound credentials: change report

**Branch:** `feature/t1-5-customer-bound-credentials`, from `feature/infrastructureUpdatesBE`. Not pushed, not merged.
**Doc:** `docs/2026-11-25-customer-bound-credentials.md`.

## What it gives

Every credential the backend issues is accepted only at the customer it was issued at. Access and refresh tokens carry
the customer's id (`tid`) and are refused elsewhere with 401 `TENANT_MISMATCH`; the other credentials already were
refused, because they are looked up in the customer's own database. The refresh cookie is `SameSite=Lax`. T1-3's
start-up warning is gone: multi mode is fit to leave the local machine, subject to Stage A.

## Audit

| Credential | How it's checked | Safe before T1-5? | What changed |
|---|---|---|---|
| Access token (JWT, bearer): API, kitchen and bar streams | Signature, expiry, role; no database lookup per request (only POS writes looked up the session) | **No**: accepted at another customer as "user id N" there | `tid` claim, checked in the JWT filter: 401 `TENANT_MISMATCH` |
| Refresh token / cookie | Signature, user, session (`sid`) in the customer's database | Yes in effect (session not found), but a user id missing there answered **500** | `tid` checked before the user lookup: 401 `TENANT_MISMATCH` |
| Device token (`deviceToken` cookie) | SHA-256 looked up in the customer's database | Yes | Nothing (tested host-only) |
| Offline grant | Signature, then looked up in the customer's `offline_grants`, matching the till | Yes | Nothing (approved: grants from before T1-5 stay syncable) |
| Offline snapshot | Not a credential (unsigned data, no PIN values) | n/a | Nothing |
| Invite token | SHA-256 looked up in the customer's database | Yes | Nothing (new test) |
| Manager-PIN authorisation tokens and sessions | Looked up in the customer's database | Yes | Nothing |
| Sign-out (bearer, expired accepted) | Ends the `sid` session in the customer's database | Yes (nothing found elsewhere); always 204 | Nothing |

## Steps

| Step | Commit | What | Lines |
|---|---|---|---|
| 1 | `12fe75e` | `TenantBinding` (claim, check, 401 `TENANT_MISMATCH`); `tid` in new tokens; checked in the JWT filter (entry point) and on refresh | +269 / -2 (main code +111 / -2, of which the new `TenantBinding` 80; the rest are tests) |
| 2 | `b52dc20` | Refresh cookie `SameSite=Lax`; cookie tests | +29 / -6 |
| 3 | `ac46ed2` | T1-3's start-up warning removed; `smoke.ps1` expects Lax and checks a token across customers | +11 / -16 |
| 4 | this commit | `docs/2026-11-25-customer-bound-credentials.md`, this report; the customer-from-address, local stack and routing docs | see `git show --stat` |

## Decisions

| Decision | Why |
|---|---|
| The marker on access and refresh tokens only (they come from one generator) | The audit: only these are checked by signature alone |
| No marker on offline grants (approved) | Already refused elsewhere by the database lookup; a marker would make grants issued before T1-5 unsyncable in multi mode |
| `tid` = the customer's id; checked whenever a customer is current | No mode flag: single mode has no current customer, so no claim and no check |
| A bearer token from elsewhere: not authenticated, the request marked, the security entry point answers 401 `TENANT_MISMATCH` | Protected paths get the code; public paths (sign-in, sign-out, the board) behave as with any unusable token, so sign-out stays an always-204 |
| One signing key for all customers | The claim separates them; keys from configuration (Key Vault in T1-0b) |
| The device cookie's SameSite left as it was (approved) | Already host-only; the prompt asked only for the refresh cookie |
| `SameSite=Lax` ships only with Front Door (approved) | With a frontend on another site refresh would stop working: Stage A |

## Where the code differed from the prompt

| Found | Resolution |
|---|---|
| Offline grants are not checked by signature alone (the prompt expected a marker on them) | No marker, approved |
| **The frontend:** `authFetch` treats a 401 with a JSON `code` as a business error (no refresh, no sign-out), so a tab open across the upgrade keeps failing with `TENANT_MISMATCH` until it is reloaded (a reload then ends in "sign in again") | Not changed here. Suggested frontend change: treat `TENANT_MISMATCH` like `SESSION_ENDED` |
| A refresh cookie whose user doesn't exist at the customer answered 500 | The `tid` check now refuses it first |

## Tests

New: `CustomerBoundTokensIntegrationTests` (5), `DeviceCookiesTest` (1). Updated: `RefreshCookiesTest`,
`AuthLogoutIntegrationTests` (Lax), `MultiModeStartupIntegrationTests` (no warning). `smoke.ps1`: 19 checks in multi mode,
including the cross-customer token and `SameSite=Lax` with refresh through Caddy. The check-all table, with its commit
hash, is in the final report.

## Open items

| For | Item |
|---|---|
| **Frontend** | Treat 401 `TENANT_MISMATCH` like `SESSION_ENDED` (end the session, show sign-in) |
| **Stage A / T1-0c** | Ship only behind Front Door (`SameSite=Lax`); `CORS_ALLOWED_ORIGINS=https://*.pos.optimumscs.com`; Key Vault for `JWT_SECRET` (T1-0b) |
| **T1-4** | Provisioning: new customers get their database migrated and registered; tokens need nothing more |
| **T1-6** | Background work: no credentials involved |
| Later | A refresh token is also accepted as a bearer access token (same claims, longer lifetime) at its own customer: a separate `typ` claim would stop that |
