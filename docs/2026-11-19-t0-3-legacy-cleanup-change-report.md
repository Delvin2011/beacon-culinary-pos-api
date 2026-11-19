# T0-3 legacy and go-live clean-up: change report

Branch `feature/t0-3-legacy-cleanup`, from `main` at `646c237`. Not pushed or merged.

T0-3 removes what was left of the original e-commerce template before new customers are onboarded. It closes go-live checklist items 5, 6 and 7 (multi-tenancy readiness analysis, sections A.1 and C.8).

## 1. Summary

| Area | Result |
|---|---|
| `USER` role | Gone from the database (V112) and the code. Former `USER` users are inactive cashiers, recorded in `legacy_role_conversions`. |
| Legacy tables | `addresses` and `profiles` are dropped. V112 refuses to run if either holds rows. |
| Legacy endpoints | `GET /users`, `GET /users/{id}`, `POST /users`, `PUT /users/{id}`, `DELETE /users/{id}`, `GET /admin/hello` and `/` are removed. |
| Kept | `GET /users/cashiers`, `GET /users/login-tiles`, `POST /users/{id}/change-password` |
| Frontend | No change needed: the frontend calls none of the removed endpoints and never uses a `USER` role. |
| Tests | Full suite on a fresh throwaway database: 927 tests, 0 failures, 0 errors, 0 skipped. V112's guard and conversion were checked there as well. |

## 2. Commits

| Step | Commit | What |
|---|---|---|
| 1 | `68a5932` | V112 migration |
| 2 | `41a031b` | `USER` role removed |
| 3 | `c8053e0` | The five legacy `/users` endpoints removed; the test helper uses `/admin/staff` |
| 4 | `c6f11a3` | Template leftovers, Thymeleaf, `Address` and `Profile` removed |
| 5 | `8e4f40f` | Tests |
| 6 | see `git log` | Docs: this report, go-live checklist, README |

## 3. Migration `V112__t0_3_legacy_cleanup.sql`

In order, in one transaction:

1. **Guard.** If `addresses` or `profiles` holds any row, the migration fails with error 50112 ("addresses or profiles contain rows … No data was changed.") and changes nothing.
2. **`USER` users.**
   - Each one is recorded in the new table `legacy_role_conversions`: `user_id` (an FK to `users`), `previous_role`, `new_role`, `migration`, and `converted_at` in UTC.
   - Each is then set to role `CASHIER`, inactive, with password and PIN cleared. Name and email are kept.
   - The count and the ids are printed to the Flyway log, for example: `T0-3 (V112): USER-role users deactivated and changed to CASHIER: 1. Ids: 9`.
3. **Role column.**
   - `DF_users_role` (default `'USER'`, from V3) is dropped. Every insert must now name a role.
   - `CK_users_role` is rebuilt without `USER`.
4. **Tables.** `FK_addresses_users` and `FK_profiles_users` are dropped, then the `addresses` and `profiles` tables.

**What this means for converted users:**
- **They can't sign in.** `CASHIER` is PIN-only, so the account can't sign in, even if an admin reactivates it, until an admin sets a PIN.
- **Their email stays taken.** `UX_users_email` is unique across active and inactive users, so a kept email can't be given to new staff until an admin changes or clears it on the former user.
- **They now appear in the staff list.** `/admin/staff` used to hide `USER` accounts. They now show there as inactive cashiers.

## 4. Code changes

**`USER` role:**
- `Role.USER` is removed.
- `StaffService` no longer filters out or refuses `USER` accounts.
- `Jwt.getRole()` returns null for a role that no longer exists. `JwtAuthenticationFilter` then treats the token as unauthenticated, so a token issued before the deploy with role `USER` gets 401, not a 500.

**The five `/users` endpoints:**
- Removed from `UserController` and `UserService`, with `UserMapper.toEntity` and `UserMapper.update`.
- Deleted classes: `RegisterUserRequest`, `UpdateUserRequest`, `DuplicateUserException`, `Lowercase`, `LowercaseValidator`.
- `UserSecurityRules` drops its ADMIN-only rule for the rest of `/users`. Removed paths fall to the global "signed in" rule: a signed-in caller gets 404 (405 for some methods), an anonymous one 401. No special rule is needed.

**Template leftovers:**
- `HomeController` and `templates/index.html` are deleted.
- `GET /admin/hello` is removed.
- `spring-boot-starter-thymeleaf` and `thymeleaf-extras-springsecurity6` are removed; nothing else used them.
- `Address`, `Profile` and their repositories are deleted, along with the `addresses` mapping in `User`.

**No references left.** `src` has no reference to `Role.USER`, `Address`, `Profile`, `HomeController` or `/admin/hello`. The only exception is `LegacyCleanupIntegrationTests`, which checks those routes are gone.

## 5. Verification on a throwaway database

The check ran on a local SQL Server, on a new database `BeaconculinaryTestT03Clean`, created for it and dropped afterwards. No fixture went into the shared test database or into a migration.

1. I created the empty database and ran the Flyway Maven plugin with `-Dflyway.target=111`, which applied 109 migrations with no failures.
2. **Fixtures:** one user with role `USER`, a password and a PIN (dummy values, not real hashes), plus one row in `addresses`.
3. **Guard with a row in `addresses`:** `flyway:migrate` failed with error 50112. The user was unchanged and V112 wasn't recorded.
4. **Guard with a row in `profiles` instead:** the same result.
5. **Real run:** with both tables empty, V112 applied. The Flyway log printed `… CASHIER: 1. Ids: 9`.
6. **Checks after V112:**
   - The fixture user is `CASHIER`, inactive, with password and PIN cleared and name and email kept.
   - `legacy_role_conversions` holds (9, USER, CASHIER, V112).
   - No `USER` rows are left.
   - The CHECK no longer lists `USER` and rejects it.
   - `DF_users_role` is gone, so an insert without a role fails.
   - Both tables and their foreign keys are gone.
   - The 8 seed staff are still active.
7. **Full suite:** run against this database, with the converted user still in it.

## 6. Tests

**New class: `users/LegacyCleanupIntegrationTests` (7 tests)**

| Test | Checks |
|---|---|
| `v112_leavesNoUserRole_noDefault_andNoLegacyTables` | No `USER` rows. The CHECK has no `USER`. No `DF_users_role`. No `addresses` or `profiles`. `legacy_role_conversions` exists. V112 recorded as successful. |
| `theDatabaseRefusesTheUserRole_andAUserWithoutARole` | Both inserts fail and leave nothing behind |
| `theRoleEnumHasNoUser` | `Role.values()` has no `USER` |
| `removedEndpoints_areGone_forAdmins_andNeedSignInLikeAnyUnknownPath` | The seven removed routes return 404 or 405 for an admin and 401 anonymously. Nothing is renamed, deleted or created. |
| `keptUserEndpoints_stillWork` | `GET /users/cashiers`, `GET /users/login-tiles` and `POST /users/{id}/change-password` |
| `aTokenStillCarryingTheUserRole_isUnauthenticated_notAServerError` | 401 on `/auth/me` and `/admin/staff` |
| `deactivatedStaff_stillNamedInShiftOrderAndAdjustmentHistory` | A cashier opens a shift, sells, has a refund authorized and closes the shift, and is then deactivated through `/admin/staff`. Their name still shows as the shift's owner and `closedBy`, the order's `rungBy` and `cashierName`, and the refund's `requestedBy`. The user row is kept, inactive. |

**Existing tests that changed**

| Test | Change | Why |
|---|---|---|
| `support/AuthTestHelper.userId` | Reads `GET /admin/staff` instead of `GET /users`, and skips staff with no email | `GET /users` is removed |
| `StaffManagementIntegrationTests.oldUsersEndpointsAreLockedDown` → `oldUsersEndpointsAreGone` | Expects 404 (or 405), and 401 for the anonymous sign-up | The routes are removed, not just admin-only |
| `StaffManagementIntegrationTests.credentialsAreValidatedAgainstTheRole` | Posting role `USER` expects 400 `{"error":"Invalid request body"}` | `USER` no longer parses as a role. The frontend builds its role list from `/admin/staff/roles`, so it never sends this. |
| `LoginValidationAdviceScopeIntegrationTests.adminController_endpointWithoutValidation_isUnchanged_andNotRecorded` | Deleted | It called `/admin/hello`. Every endpoint left on `AdminController` validates its body, so there's nothing to point it at. The class's other 5 tests stay. |

**Results**

| Run | Result |
|---|---|
| Full suite before the test changes (steps 1–4) | 921 tests, 3 failures, exactly the 3 changed tests above. No errors, nothing skipped. |
| `LegacyCleanupIntegrationTests` | 7 of 7 pass |
| Full suite after the test changes | **927 tests, 0 failures, 0 errors, 0 skipped** (921 + 7 new − 1 deleted). BUILD SUCCESS in 7 min 31 s. |

## 7. Frontend

The frontend team checked their repository with the T0-3 frontend prompt. They found:
- no calls to the five removed `/users` endpoints, `/admin/hello` or the API root;
- no use of a `USER` role;
- a staff role list that comes from `/admin/staff/roles`;
- no code that treats a 404 as being signed out.

The `"USER"` the frontend sees in reports is a different thing: a report parameter type (`reports/ParamType.USER`), which is unchanged. No frontend change is needed.

## 8. Before merging, and follow-ups

- **Run the Step 0 read-only query on dev and production.** If `addresses` or `profiles` holds rows, V112 will stop. Decide what to do with those rows first: export them, or remove them deliberately.
- **Dev doesn't migrate itself.** The `dev` profile turns Flyway off, so V112 has to be applied to dev deliberately (`docs/2026-11-11-running-flyway-locally.md`).
- **Migration numbers.** Any other branch that adds a migration after this one must use V113 or later.
- **Go-live checklist item 12** (foreign keys on the device audit columns) is now unblocked, but still open.

## 9. Files

**Created**
- `src/main/resources/db/migration/V112__t0_3_legacy_cleanup.sql`
- `src/test/java/com/beaconculinary/api/users/LegacyCleanupIntegrationTests.java`
- `docs/2026-11-19-t0-3-legacy-cleanup-change-report.md`

**Modified**
- `pom.xml`
- `src/main/java/com/beaconculinary/api/admin/AdminController.java`
- `src/main/java/com/beaconculinary/api/auth/Jwt.java`
- `src/main/java/com/beaconculinary/api/auth/JwtAuthenticationFilter.java`
- `src/main/java/com/beaconculinary/api/staff/StaffService.java`
- `src/main/java/com/beaconculinary/api/users/Role.java`
- `src/main/java/com/beaconculinary/api/users/User.java`
- `src/main/java/com/beaconculinary/api/users/UserController.java`
- `src/main/java/com/beaconculinary/api/users/UserMapper.java`
- `src/main/java/com/beaconculinary/api/users/UserSecurityRules.java`
- `src/main/java/com/beaconculinary/api/users/UserService.java`
- `src/test/java/com/beaconculinary/api/support/AuthTestHelper.java`
- `src/test/java/com/beaconculinary/api/staff/StaffManagementIntegrationTests.java`
- `src/test/java/com/beaconculinary/api/auth/protection/LoginValidationAdviceScopeIntegrationTests.java`
- `docs/2026-11-11-go-live-checklist.md`
- `README.md` (rewritten)

**Deleted**
- `src/main/java/com/beaconculinary/api/common/HomeController.java`
- `src/main/resources/templates/index.html`
- `src/main/java/com/beaconculinary/api/users/Address.java`
- `src/main/java/com/beaconculinary/api/users/AddressRepository.java`
- `src/main/java/com/beaconculinary/api/users/Profile.java`
- `src/main/java/com/beaconculinary/api/users/ProfileRepository.java`
- `src/main/java/com/beaconculinary/api/users/RegisterUserRequest.java`
- `src/main/java/com/beaconculinary/api/users/UpdateUserRequest.java`
- `src/main/java/com/beaconculinary/api/users/DuplicateUserException.java`
- `src/main/java/com/beaconculinary/api/users/Lowercase.java`
- `src/main/java/com/beaconculinary/api/users/LowercaseValidator.java`
