# Go-live checklist (production blockers)

Status at branch `feature/ws1-1-login-protection`, based on `main` at `f9cc9f2`. The system runs only in Azure dev today; there is no production database yet. Each item below must be closed or explicitly accepted before production. Credential values are never written here.

Paths are relative to the repository root. Java paths are under `src/main/java/com/beaconculinary/api/`; migrations under `src/main/resources/db/migration/`.

## Summary

| # | Item | Severity | Status | Owner |
|---|---|---|---|---|
| 1 | Seed accounts with one shared, documented credential | Blocker | Closed for new customers in T0-7 (baseline without demo data); existing databases: refused under prod since T0-6a, deactivate them | TBD |
| 2 | Datasource credentials in config files and git history | Blocker | Config fixed in WS1-1 0-A/0-B; history and rotation open | TBD |
| 3 | Flyway `clean` enabled in the Maven plugin | Blocker | Fixed in WS1-1 0-A | TBD |
| 4 | Swagger UI and API docs public | High | Fixed for the prod profile in WS1-1 0-C | TBD |
| 5 | `Role.USER` can log in by password | High | Closed in T0-3 (V112; `USER` role removed) | TBD |
| 6 | `DELETE /users/{id}` hard-deletes staff | High | Closed in T0-3 (endpoint removed) | TBD |
| 7 | Template leftovers reachable after login | Low | Closed in T0-3 | TBD |
| 8 | Public login-tile endpoints list every PIN user | Medium | Open (WS1-1 Phase 2 context); demo accounts left out under prod since T0-6a | TBD |
| 9 | Access tokens ignore deactivation for up to 15 minutes | Medium | Open | TBD |
| 10 | Production profile not confirmed for Azure | **High** since T0-6a (the demo guard depends on it) | Open | TBD |
| 11 | Frontend and API on separate default Azure hostnames (cookies are cross-site) | Blocker | Open (WS1-1b decision K1) | TBD |
| 12 | No foreign keys on the device audit columns | Low | Open; unblocked by T0-3 (item 6 closed) | TBD |
| 13 | Two error response shapes (`ErrorDto`, `LoginProtectionErrorDto`) | Low | Tidy-up, not in WS1-1b | TBD |
| 14 | Refresh tokens can't be revoked (logout only clears the cookie) | Medium | Open | TBD |
| 15 | Customer settings set at onboarding (business name, first trading day, VAT rate, meal periods, stock locations) | Medium | Open (T0-4a, T0-5a, T0-5c) | TBD |

## 1. Seed accounts

**What.** Versioned migrations create staff accounts and set their passwords and PINs. The plaintext values are written in migration comments, and in one migration's file name. They are identical for every account.

**Where.**

| Migration | Lines | Account | Email | Role | Credential set |
|---|---|---|---|---|---|
| `V13__seed_menu_and_users.sql` | 12-14 | Canteen Cashier | `cashier@canteen.local` | CASHIER | password |
| `V13__seed_menu_and_users.sql` | 12-14 | Canteen Admin | `admin@canteen.local` | ADMIN | password |
| `V14__add_pin_auth_to_users.sql` | 12-13 | (the two above) | | | PIN |
| `V14__add_pin_auth_to_users.sql` | 15-16 | Canteen Cashier B | `cashierb@canteen.local` | CASHIER | password, PIN |
| `V32__seed_kitchen_kiosk_user.sql` | 10-13 | Kitchen Station | `kitchen@canteen.local` | KITCHEN | PIN (password unusable) |
| `V34__reset_dev_credentials_to_[REDACTED].sql` | 12-25 | all of the above | | | password and PIN reset |
| `V69__seed_stock_clerk_and_stock_admin_users.sql` | 9-17 | Stock Clerk, Stock Admin | `stockclerk@…`, `stockadmin@…` | STOCK_CLERK, STOCK_ADMIN | password, PIN |
| `V89__head_chef_role_and_cancellable_requests.sql` | 20-23 | Head Chef | `headchef@canteen.local` | HEAD_CHEF | password, PIN |
| `V98__seed_barista.sql` | 3-4 | Canteen Barista | `barista@canteen.local` | BARISTA | PIN |

No migration forces IDs with `IDENTITY_INSERT`. The V14 comments say IDs 1, 2 and 3; those follow only from insert order on an empty table.

Other migrations depend on the seeds: `V58__seed_ingredients.sql:15` looks up `admin@canteen.local` to fill `created_by` on seeded stock rows. Business seed data (menu, catalog, ingredients, recipes) is in V13, V27, V29, V58 and V59.

**Why it matters.** Anyone who has seen the repository knows a working admin login and admin PIN. Lockout and rate limiting (WS1-1) cannot protect an account whose credential is public.

**Tests that depend on the seeds** (counts are test files):

| Helper in `src/test/java/.../support/AuthTestHelper.java` | Seed account | Files |
|---|---|---|
| `loginAsAdmin`, `adminId` | admin | 48, 17 |
| `loginAsCashier`, `cashierAId` | cashier | 32, 12 |
| `cashierBId` | cashier B | 10 |
| `loginAsStockAdmin` | stock admin | 14 |
| `loginAsKitchen`, `kitchenId` | kitchen | 13, 1 |
| `loginAsStockClerk` | stock clerk | 9 |
| `loginAsHeadChef` | head chef | 7 |
| `loginAsBarista` | barista | 2 |

`StaffManagementIntegrationTests` also uses seed emails and the seeded admin PIN directly. `AuthPinLoginIntegrationTests` uses cashier A.

### Options for a production database without these accounts

**Option A: move dev seeds to a dev/test-only Flyway location** (for example `classpath:db/dev-seed`, added to `spring.flyway.locations` only in dev and test).

- Flyway checksums depend on file content, not location, so an unchanged file moved to another location still validates in existing dev and test databases, as long as that location stays configured there.
- **It does not work cleanly here.** V13 (menu and users), V14 (adds `pin_hash` and `active`, then seeds) and V89 (role constraint, then seeds) mix schema changes with seed rows. Moving them would also move schema changes that production needs. Splitting them changes their content, which changes checksums, so existing dev and test databases fail validation unless someone runs `flyway repair`.
- A fresh production database would also fail at, or silently NULL, V58's `created_by`, because the admin it looks up would not exist.
- Business seed data would need the same treatment, and it is not clear whether production wants the seeded menu at all.
- Effort: 3–5 days including repair runbooks. Risk: high (checksum drift, an ordering mistake breaks a fresh database).

**Option B: production bootstrap plus a startup guard (recommended).**

- Keep every migration as it is. A fresh production database is created with the seeds.
- **Guard:** under the `prod` profile, refuse to start while any known seed account (`*@canteen.local`, or the list above) is active, with a message that names the bootstrap step.
- **Bootstrap:** a one-time command (for example a Spring `ApplicationRunner` behind `--bootstrap-admin`, run once from the Azure console) that:
  1. creates the first real admin from secure input: name and email as arguments, password and PIN generated and printed once, never logged;
  2. deactivates every seed account and clears its password and PIN hashes;
  3. records who ran it and when.
- A forced password change at first login needs a `must_change_password` column and an endpoint, so this is a separate ticket. Until then, the generated password is handed over in person and changed by the admin straight away.
- Effort: 2–3 days. Risks: seeded menu and stock data stays in production (decide separately whether to keep it); an admin could reactivate a seed account (the guard stops a restart, and staff management could refuse the reserved emails).

**Recommendation:** Option B. It needs no change to applied migrations, keeps dev and test exactly as they are, and makes "production still has seeds" impossible to miss at startup.

**Done in T0-6a (V116), a variant of option B.** Runbook: `docs/2026-11-21-first-admin-and-invites.md`; API: `docs/2026-11-21-t0-6-invites-api-contract.md`.

- The eight seed accounts are marked `users.is_demo`. Under the `prod` profile (or `security.demo-accounts.block-sign-in=true`) they are **refused** by password and PIN sign-in, manager-PIN approval, refresh, change password and invite links, and left off the login tiles. Each refusal is recorded (`DEMO_ACCOUNT`) and alerted. Startup logs `LOGIN_PROTECTION_ALERT type=DEMO_ACCOUNTS_ACTIVE count=N` while any is active.
- **Blocked rather than refusing to start**: deactivating them without SQL needs a real admin, who is set up through an invite link, which needs the app running.
- **First admin**: the operator's `FirstAdminCommand` (run from the app jar on a workstation, not from Azure) creates the admin without credentials and prints a single-use 72-hour link; the admin sets their own password and PIN. Nobody else ever knows them, so no forced password change is needed. `--force --reason` (recorded on the invite) only when no existing admin can sign in.
- Staff set their own credentials from links too (`POST /admin/staff/{id}/invite`).
- **Still to do at each go-live:** the first admin deactivates the demo accounts from the staff screen. The seeded menu and stock data remain a separate decision.
- Depends on item 10: without the prod profile (and without the setting) the guard is off.

**T0-7: new customers start without them.** A new customer's database is built from the B116 baseline (`docs/2026-11-22-new-customer-database.md`): the schema with only the required reference data, so it has no demo accounts, demo menu or demo stock at all. The guard and the deactivation step above remain for databases with full migration history (OptimumSCS). `baseline-on-migrate` is now off: before deploying to an existing database, check its `flyway_schema_history` (that doc, section 8).

## 2. Datasource credentials in config files and history

**What and where.**

- `pom.xml:169-171` (before WS1-1 0-A): Flyway plugin URL, user and password as literals. [REDACTED]
- `src/main/resources/application-dev.yaml:3-6` (before WS1-1 0-B): datasource URL, user and password as literals. [REDACTED]
- Git history (report only; history is not rewritten): literal datasource passwords were committed in `e91d1c8` (`pom.xml`, `application.yaml`), `e16b8b3` (`application-dev.yaml`) and `96a290b` (`application-dev.yaml`). The JWT secret and the old Stripe keys were only ever placeholders. `.env` has never been committed.

**Why it matters.** Anyone with repository access has the SQL login that was used in those files.

**Proposed fix.** Done in config by WS1-1 0-A and 0-B. Still open: rotate that SQL password and any database that shares it; give production its own SQL login with least privilege; keep secrets in Azure App Settings or Key Vault.

## 3. Flyway clean

**What.** `pom.xml:173` had `<cleanDisabled>false</cleanDisabled>`, so `mvn flyway:clean` would drop the whole schema of whatever database the plugin points at.

**Proposed fix.** Set to `true` in WS1-1 0-A. Keep it that way, and never give the plugin production credentials.

## 4. Swagger and OpenAPI

**What.** `common/SwaggerSecurityRules.java:12-14` makes `/swagger-ui/**`, `/swagger-ui.html` and `/v3/api-docs/**` public.

**Why it matters.** It publishes the full API map, including admin routes, to anyone.

**Proposed fix.** Disabled under the `prod` profile in WS1-1 0-C (`springdoc.api-docs.enabled` and `springdoc.swagger-ui.enabled` set to `false`). Kept in dev.

## 5. `Role.USER` password login

**What.** `users/Role.java:4` keeps the e-commerce `USER` role. `users/UserDetailsServiceImpl.java:18-32` lets any user with a password log in, whatever the role. `users/UserService.java:47-58` (`POST /users`, admin only) still creates `USER` accounts.

**Why it matters.** A `USER` account can reach every endpoint that only requires authentication (`auth/SecurityConfig.java:81`), such as `GET /auth/me`, `GET /` and change-password. No staff role needs this.

**Proposed fix.** Refuse login for `USER`, remove `POST /users`, and migrate or deactivate any `USER` rows. Separate ticket.

**Status: closed in T0-3** (`docs/2026-11-19-t0-3-legacy-cleanup-change-report.md`). `V112__t0_3_legacy_cleanup.sql` turns every `USER` row into an inactive `CASHIER` with password and PIN cleared, never deleting it; each one is recorded in `legacy_role_conversions` and its id printed to the Flyway log. `USER` is gone from the role CHECK, the `'USER'` column default and `Role`, and `POST /users` is removed. A converted user keeps their email, and emails are unique across active and inactive users, so that email can't be given to new staff until an admin changes or clears it.

## 6. `DELETE /users/{id}` hard delete

**What.** `users/UserController.java:63-66` → `users/UserService.java:68-71` deletes the user row. Admin only (`users/UserSecurityRules.java:20`).

**Why it matters.** Staff management promises "deactivate, never delete" (`staff/StaffService.java:26-27`). A hard delete fails on foreign keys for any user with orders or shifts, or removes history for one without. It also bypasses the last-admin check.

**Proposed fix.** Remove the endpoint (staff are managed through `/admin/staff`) or make it deactivate. Separate ticket.

**Status: closed in T0-3.** `DELETE /users/{id}` is removed, together with `GET /users`, `GET /users/{id}`, `POST /users` and `PUT /users/{id}`. Staff are listed, created, edited and deactivated only through `/admin/staff`. `GET /users/cashiers`, `GET /users/login-tiles` and `POST /users/{id}/change-password` stay.

## 7. Template leftovers reachable after login

| What | Where | Reachable by | Fix |
|---|---|---|---|
| `HomeController` Thymeleaf "Hello" page | `common/HomeController.java:9-14`, `src/main/resources/templates/index.html` | any signed-in user | Remove, with the Thymeleaf dependencies if unused |
| `GET /admin/hello` | `admin/AdminController.java:16-19` | ADMIN | Remove |
| `Address` entity and repository | `users/Address.java`, `users/AddressRepository.java`, `users/User.java:43-55` | no endpoint | Remove with a migration that drops the table |
| `PUT /users/{id}`, `GET /users` | `users/UserController.java:20-61` | ADMIN | Fold into `/admin/staff` |

**Status: closed in T0-3.** All four rows are removed: `HomeController`, `templates/index.html` and both Thymeleaf dependencies; `GET /admin/hello`; `Address` and `Profile` with their repositories and the mapping in `User`, after V112 drops the `addresses` and `profiles` tables (it refuses to run if either holds rows); and the legacy `/users` endpoints (item 6). A signed-in caller of a removed route gets 404 (405 for some methods); an anonymous one gets 401.

## 8. Public login-tile endpoints

**What.** `GET /users/login-tiles` and `GET /users/cashiers` (`users/UserController.java:29-39`, public by `users/UserSecurityRules.java:15`) return the ID and name of every active PIN user, admins included.

**Why it matters.** It lets an attacker target every account with PIN guesses or lockouts.

**Proposed fix.** Decide in WS1-1 Phase 2 or the device-trust task (for example, require a registered device).

## 9. Access tokens ignore deactivation

**What.** `auth/JwtAuthenticationFilter.java:31-47` trusts the token without checking the user. Refresh does check `active` (`auth/AuthService.java:81-85`).

**Why it matters.** A deactivated or locked user keeps access for up to 15 minutes.

**Proposed fix.** Accept the 15-minute window, or add a token version or active check. Decide with WS1-1 Phase 2.

## 10. Production profile

**What.** Swagger, cookie security and Flyway behaviour depend on which profile runs. It is not confirmed which profile Azure dev uses, or will use in production.

**Proposed fix.** Production runs with `SPRING_PROFILES_ACTIVE=prod`. Add a startup log line that states the active profiles.

**T0-6a.** The demo-account guard (item 1) is always on under the prod profile, so confirming the profile is now more urgent. Check the startup line: it says `demo guard on` (and `off` without the profile).

## 11. Custom domains for the frontend and API

**What.** Default Azure hostnames (`*.azurewebsites.net`, `*.azurestaticapps.net`, `*.azurecontainerapps.io`) are on the Public Suffix List, so a frontend and API on them are cross-site. The `refreshToken` cookie and the WS1-1b `deviceToken` cookie are then third-party cookies: dropped in Incognito/InPrivate, in Safari, and wherever third-party cookies are blocked.

**Where.** `auth/AuthController.java` (refresh cookie), the WS1-1b device cookie, and CORS in `auth/SecurityConfig.java`.

**Why it matters.** Till registration and sign-in sessions depend on these cookies.

**Proposed fix.** Serve the frontend and API on custom domains under one registrable domain (for example `pos.<domain>` and `api.<domain>`), set `CORS_ALLOWED_ORIGINS` to the frontend domain, and keep tills on Chrome or Edge in kiosk mode.

## 12. Foreign keys on the device audit columns

**What.** `devices.registered_by`, `devices.revoked_by`, `device_credentials.created_by` and `device_credentials.revoked_by` (WS1-1b) have no foreign key to `users`. Name snapshots are stored instead, because `DELETE /users/{id}` hard-deletes users (item 6).

**Proposed fix.** Add the foreign keys once item 6 turns the delete into a deactivation.

**Status:** still open, but unblocked: T0-3 removed the hard delete (item 6). Adding the foreign keys is a separate task.

## 13. One error response shape

**What.** 429 responses use `LoginProtectionErrorDto` `{code, message, retryAfterSeconds}`; other errors use `ErrorDto` `{error, code, details?}`.

**Proposed fix.** Unify them into one shape in a later release, together with the frontend. Clients already branch on `code`, so the change is contained.

## 14. Refresh tokens can't be revoked

**What.** Refresh tokens are stateless JWTs, valid for 7 days (`jwt.refreshTokenExpiration`). `POST /auth/logout` (WS1-1b follow-up) only deletes the `refreshToken` cookie from the browser: the cookie's path is `/auth/refresh`, so it never reaches `/auth/logout`, and the server keeps no list of issued tokens. Refresh already refuses inactive users and, in enforce mode, locked ones (`auth/AuthService.java`).

**Why it matters.** A refresh token copied from a browser keeps issuing access tokens until it expires, even after the user logs out. Revoking a till doesn't end sessions started on it either (WS1-1c).

**Proposed fix.** Store refresh tokens server-side (an ID claim and a table, or a per-user token version), revoke on logout, and rotate on each refresh. To revoke on logout, the cookie must reach the logout endpoint: widen its path to `/auth`, or move logout under `/auth/refresh`.

## 15. Customer settings at onboarding

**What.** Since T0-4a the customer's own settings live in their database (docs/2026-11-20-site-settings.md). `app.business.name` is blank by default, so statement reports print no business name until the receipt settings have one.

**Fix.** At onboarding: set the business name (and VAT number) in the receipt settings; set `reports.close-day.first-trading-day` to the go-live day before the first Z; review the VAT rate and the offline limits in `/admin/settings`.

**Meal periods (T0-5a).** The seeded Breakfast 07:00–10:00, Lunch 12:00–14:30 and All Day are defaults. At onboarding, set the customer's own names and times in `/admin/meal-periods` before planning the first day (a period with items planned today or later can't be deactivated).

**Stock locations (T0-5c).** Main Store (the primary: receives every GRV, supplies every issue) and Kitchen (production and every station) are seeded. At onboarding, rename them to the customer's own names and add any other locations in `/admin/locations`, then set the production location and each station's source in `/admin/locations/assignments` before the first GRV and the first planning day. At most `stock.locations.max-active` (default 10) can be active. See docs/2026-11-21-stock-locations.md.
