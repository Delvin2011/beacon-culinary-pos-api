# Customer-bound credentials (T1-5)

In multi-customer mode, every credential the backend issues is accepted only at the customer it was issued at. A
credential from customer A presented at customer B is refused. **Single mode is unchanged.**

## 1. The credentials, and how each is bound

| Credential | Checked by | Bound to its customer by |
|---|---|---|
| Access token (JWT, bearer): API, kitchen and bar streams | Signature and expiry; no database lookup per request | **The `tid` claim (T1-5)**, checked against the customer of the address (T1-3) |
| Refresh token (the `refreshToken` cookie) | Signature, the user, the session (`sid`) in the customer's database | **The `tid` claim (T1-5)**, checked before the user lookup. The session lookup refused it already |
| Device token (the `deviceToken` cookie) | SHA-256 looked up in the customer's `device_credentials` | Its database: not found at another customer |
| Offline grant (signed by the server, verified only by the server) | Signature, then the grant looked up in the customer's `offline_grants`, matching the till | Its database: not found at another customer. No claim added: grants issued before T1-5 can still be synced |
| Invite token | SHA-256 looked up in the customer's `user_invites` | Its database |
| Manager-PIN authorisation tokens and sessions | Looked up in the customer's `authorization_tokens` and `authorization_sessions` | Its database |
| Offline snapshot | Not a credential: menu data and a version hash, unsigned, no PIN values | n/a |

## 2. The `tid` claim

- **In multi mode**, every access and refresh token issued at a customer carries that customer's id as `tid`
  (`auth/TenantBinding`, added in `JwtService`).
- **The rule:** a token is accepted only when its `tid` is the id of the customer the request's address belongs to.
  A token **without** `tid` is refused in multi mode: tokens from before T1-5 sign in again once.
- **In single mode** there is no current customer: no claim is added and nothing is checked, as before.
- **One signing key for every customer**, from configuration (`JWT_SECRET`; Key Vault in T1-0b). The claim does the
  separation, so there are no per-customer keys.

**Refusal:** **401** with the usual error body:
`{"error":"This sign-in is for another address. Sign in again.","code":"TENANT_MISMATCH"}`.
- **Bearer tokens:** the JWT filter doesn't authenticate the request, and the security entry point answers.
- **Public paths** (sign-in, sign-out, the board) carry on as with any unusable token, so sign-out stays an always-204.
- **The refresh cookie:** `/auth/refresh` answers the same.
- **Logging:** each refusal is logged at WARN, `TENANT_MISMATCH: a token issued for customer <id> was presented at
  customer <slug>`, with the request id. The token is never logged.

**Beacon's move (T1-11):** at its new address the browser has no tokens or cookies yet, so everyone signs in there
anyway. Tokens from the single-mode deployment never reach it.

## 3. Cookies

| Cookie | Domain | SameSite | Why |
|---|---|---|---|
| `refreshToken` | none (host-only) | **Lax** (was None) | The app and the API share one address (T1-0d): the browser sends it on its own sign-in, refresh and sign-out, never from another site |
| `deviceToken` | none (host-only) | unchanged | Already host-only; left as it was |

**Host-only:** a cookie set at `beaconculinary.pos.localhost` is never sent to `testcustomer.pos.localhost`.

**Stage A: `SameSite=Lax` needs the frontend and the API on the same site.** With a frontend on another site (as
before Front Door), refresh would stop working. This backend must reach production only together with Front Door (T1-0c).

## 4. Multi mode can leave the local machine

T1-3's start-up warning ("multi mode without customer-bound tokens: local development only") is gone. Multi mode is
fit for an Azure environment, subject to Stage A: Front Door (T1-0c), Key Vault (T1-0b), and the other Stage A items.

## 5. The frontend

**No change needed for normal use.** Each address has its own browser storage and cookies, so the frontend never
sends one customer's token to another.

**One case behaves badly:** a tab already open when the backend is upgraded to T1-5 in multi mode. Its in-memory
access token has no `tid`, so its calls answer 401 `TENANT_MISMATCH`.
- `authFetch` treats a 401 that has a JSON `code` as a business error. It neither refreshes nor ends the session, so
  the page stays "signed in" with failing calls until it is reloaded.
- A reload refreshes with the cookie and gets 401 (`TENANT_MISMATCH`), and the frontend then ends the session:
  "sign in again".
- **Suggested frontend change:** treat `TENANT_MISMATCH` like `SESSION_ENDED` (end the session, show sign-in).
  Not done here.

## 6. Tests

| Test | Proves |
|---|---|
| `CustomerBoundTokensIntegrationTests` (5) | An access token from alpha at bravo gets 401 `TENANT_MISMATCH` on a back-office read, a POS write and the kitchen stream (no stream opens), and works at alpha. A refresh cookie from alpha at bravo gets 401 `TENANT_MISMATCH`, and works at alpha. A token without `tid` is refused in multi mode. New tokens carry their customer. An invite token from alpha is refused at bravo. A WARN line with the request id, and no token in the output |
| `CustomerFilterOrderIntegrationTests` (T1-3) | A device cookie from alpha isn't recognised at bravo |
| `MultiCustomerOfflineSyncIntegrationTests` (T1-3) | A grant from alpha creates nothing at bravo; synced at alpha it's accepted |
| `MultiCustomerIsolationIntegrationTests` (T1-2) | A manager authorisation token from alpha is refused at bravo |
| `RefreshCookiesTest`, `AuthLogoutIntegrationTests`, `DeviceCookiesTest` | `SameSite=Lax`, no Domain on either cookie |
| The full suite (single mode) | Tokens without a customer work as before |
| `smoke.ps1` (multi) | `SameSite=Lax` and refresh through Caddy at both customers; beaconculinary's token at testcustomer gets 401 `TENANT_MISMATCH` |
