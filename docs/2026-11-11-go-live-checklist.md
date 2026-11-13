# Go-live checklist (production blockers)

Status at branch `feature/ws1-1-login-protection`, based on `main` at `f9cc9f2`. The system runs only in Azure dev today; there is no production database yet. Each item below must be closed or explicitly accepted before production. Credential values are never written here.

Paths are relative to the repository root. Java paths are under `src/main/java/com/beaconculinary/api/`; migrations under `src/main/resources/db/migration/`.

## Summary

| # | Item | Severity | Status | Owner |
|---|---|---|---|---|
| 1 | Seed accounts with one shared, documented credential | Blocker | Open (proposal below) | TBD |
| 2 | Datasource credentials in config files and git history | Blocker | Config fixed in WS1-1 0-A/0-B; history and rotation open | TBD |
| 3 | Flyway `clean` enabled in the Maven plugin | Blocker | Fixed in WS1-1 0-A | TBD |
| 4 | Swagger UI and API docs public | High | Fixed for the prod profile in WS1-1 0-C | TBD |
| 5 | `Role.USER` can log in by password | High | Open | TBD |
| 6 | `DELETE /users/{id}` hard-deletes staff | High | Open | TBD |
| 7 | Template leftovers reachable after login | Low | Open | TBD |
| 8 | Public login-tile endpoints list every PIN user | Medium | Open (WS1-1 Phase 2 context) | TBD |
| 9 | Access tokens ignore deactivation for up to 15 minutes | Medium | Open | TBD |
| 10 | Production profile not confirmed for Azure | Medium | Open | TBD |

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

## 6. `DELETE /users/{id}` hard delete

**What.** `users/UserController.java:63-66` → `users/UserService.java:68-71` deletes the user row. Admin only (`users/UserSecurityRules.java:20`).

**Why it matters.** Staff management promises "deactivate, never delete" (`staff/StaffService.java:26-27`). A hard delete fails on foreign keys for any user with orders or shifts, or removes history for one without. It also bypasses the last-admin check.

**Proposed fix.** Remove the endpoint (staff are managed through `/admin/staff`) or make it deactivate. Separate ticket.

## 7. Template leftovers reachable after login

| What | Where | Reachable by | Fix |
|---|---|---|---|
| `HomeController` Thymeleaf "Hello" page | `common/HomeController.java:9-14`, `src/main/resources/templates/index.html` | any signed-in user | Remove, with the Thymeleaf dependencies if unused |
| `GET /admin/hello` | `admin/AdminController.java:16-19` | ADMIN | Remove |
| `Address` entity and repository | `users/Address.java`, `users/AddressRepository.java`, `users/User.java:43-55` | no endpoint | Remove with a migration that drops the table |
| `PUT /users/{id}`, `GET /users` | `users/UserController.java:20-61` | ADMIN | Fold into `/admin/staff` |

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
