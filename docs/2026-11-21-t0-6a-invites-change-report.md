# T0-6a invite links, first admin and demo accounts: change report

Branch `feature/infrastructureUpdatesBE` (on top of T0-5c, as agreed; not branched from main). Migration V116.
Contract: `docs/2026-11-21-t0-6-invites-api-contract.md`. Runbook: `docs/2026-11-21-first-admin-and-invites.md`.

## What changed

Before T0-6a, the only way to give someone a sign-in was for an admin to type their password and PIN, and every
database carried eight seeded accounts with one shared, published credential (go-live checklist item 1). Now:

- **Invite links.** Staff set their own password and/or PIN from a single-use link (`{base}/invite#t=<token>`,
  256-bit token, SHA-256 stored, 72 hours by default). Admins send set-up and reset links from the staff screen, or
  create a person with `setup: INVITE`. Accepting ends every session of the person and never signs anyone in.
- **First admin.** The operator's `FirstAdminCommand`, run from the app jar on a workstation, creates the
  customer's first admin without credentials and prints their link to that terminal only.
- **Demo accounts** (`users.is_demo`) are refused by every way in under the `prod` profile, recorded and alerted,
  and left off the login tiles. The startup log counts the active ones.
- **Login protection** covers invites (method `INVITE`, per-IP rule `INVITE_IP`).

Behaviour without links and outside `prod` is unchanged; staff responses gain `invite`.

## Steps and commits

| Step | Commit | What |
|---|---|---|
| 1 | `203ce5d` | V116: `users.is_demo` (8 seeds), `user_invites`, INVITE / DEMO_ACCOUNT codes, CREDENTIALS_CHANGED |
| 2 | `0944de0` | Token, store and link; site setting `auth.invites.expiry-hours`; `app.public-base-url` |
| 3 | `c5063eb` | Public lookup and accept; INVITE login protection; sessions ended on accept |
| 4 | `7f96128` | Staff invite, revoke, create by invite; invite status in the staff list; re-auth for admins |
| 5 | `281854b` | Demo guard on password, PIN, manager PIN, refresh, change password, invites, tiles; startup report |
| 6 | `baf5473` | `FirstAdminCommand`; `NoConsolePrintingTest` allows exactly that file |
| 7 | `4f45d2b` | Tests; fix to the command's lock |
| 8 | (this) | Docs |

## Decisions taken during the work

1. **The token never travels in a URL the server sees.** `RequestContextFilter` logs every request's path, so the
   prompt's `GET /auth/invites/{token}` would have written tokens to Azure. Lookup and accept are POSTs with the
   token in the body, and the link carries it after `#`.
2. **Three invite codes** (`INVITE_INVALID`, `INVITE_EXPIRED`, `INVITE_USED`): a 256-bit token can't be guessed,
   so telling a real holder which case applies reveals nothing, and they need different screens.
3. **Accepting never reactivates** a deactivated person (`INVITE_INVALID`); deactivating or changing role revokes an
   open link.
4. **Re-authentication for any link to another ADMIN** (not only resets), including creating an admin by invite.
5. **Demo accounts are blocked, not a refusal to start**: deactivating them without SQL needs a real admin, set up
   through an invite, which needs the app running. The guard is forced on by the `prod` profile and can be switched
   on elsewhere (`security.demo-accounts.block-sign-in`). Refusals answer exactly like a wrong credential.
6. **The manager PIN** matches against every admin, so a demo admin's PIN would still have approved voids: the guard
   covers that path, refresh (existing sessions) and the login tiles too.
7. **The first-admin command** is a plain main class launched with `PropertiesLauncher`: no Spring, no logger, so the
   link reaches only the operator's terminal. It refuses when an active, non-demo admin with a password exists,
   unless `--force --reason` (stored on the invite). Rerunning for an unfinished admin replaces their link.
8. **`INVITE` is never an account scope**: it isn't written to `user_auth_state` (whose check forbids it), and
   "unlock all" covers the four account scopes only.

## Found, not changed

- Access tokens already issued stay valid until they expire (checklist item 9); refresh and session-checked requests
  stop sooner. A manager-approval token issued to a demo admin before the guard was on lasts its few minutes.
- With login protection in `monitor` (the default), `INVITE_IP` alerts but doesn't block; the `/auth/**` burst
  limit applies either way.
- The guard depends on the `prod` profile actually being active on Azure (checklist item 10, raised to High).
- The command prints `expiresAt` with fractions of a second; cosmetic.
- The readiness analysis (sections C.8, H.20, H.22) isn't in the repository; the work followed the prompt and the
  code.

## Tests

- New: `InviteTokensTest` (6), `InviteIntegrationTests` (10), `InviteProtectionEnforceIntegrationTests` (1),
  `DemoAccountGuardIntegrationTests` (8), `FirstAdminCommandIntegrationTests` (6), and a second test in
  `NoConsolePrintingTest`.
- Adjusted: the three settings tests that count the registry (16 → 17, and the sensitive list);
  `LoginProtectionTestDefaults` pins the demo guard off for the suite.
- Full suite: **1168 tests, 0 failures, 0 errors, 1 skipped** (`StaffManagementIntegrationTests`, skipped before).
- Manual: the runbook command run from the built jar through `PropertiesLauncher` against the test database
  (forced, since that database has set-up admins): exit 0, the admin and a 72-hour FIRST_ADMIN invite created; rows
  removed afterwards.

## Files

**Created**
- `src/main/resources/db/migration/V116__t0_6a_invites_and_demo_accounts.sql`
- `src/main/java/com/beaconculinary/api/auth/invite/`: `InviteTokens`, `InviteLinks`, `InvitePurpose`,
  `InviteCredentials`, `InviteStatus`, `InviteRevokeReason`, `UserInvite`, `UserInviteStore`, `InviteRuleException`,
  `InviteDtos`, `InviteAdminDtos`, `InviteService`, `InviteController`
- `src/main/java/com/beaconculinary/api/auth/DemoAccountGuard.java`
- `src/main/java/com/beaconculinary/api/ops/FirstAdminCommand.java`
- Tests: `auth/invite/InviteTokensTest`, `auth/invite/InviteIntegrationTests`,
  `auth/invite/InviteProtectionEnforceIntegrationTests`, `auth/DemoAccountGuardIntegrationTests`,
  `ops/FirstAdminCommandIntegrationTests`
- Docs: `2026-11-21-t0-6-invites-api-contract.md`, `2026-11-21-first-admin-and-invites.md`, this report

**Modified**
- `auth/`: `AuthService`, `AuthSecurityRules`; `auth/protection/`: `LoginMethod`, `LoginOutcome`, `LoginRule`,
  `LoginRuleEvaluator`, `LoginProtectionProperties`, `LoginProtectionStore`, `LoginAttemptService`
- `admin/AdminAuthorizationService`; `users/User`, `users/UserService`
- `staff/`: `StaffService`, `StaffController`, `StaffDto`, `StaffRequests`
- `sessions/`: `SessionEndReason`, `SessionStore`; `common/GlobalExceptionHandler`
- `settings/`: `SiteSetting`, `SiteSettingDefaults`, `SiteSettings`
- `application.yaml`, `application-prod.yaml`, `.env.example`
- Tests: `SiteSettingRegistryTest`, `SiteSettingsAdminIntegrationTests`, `SiteSettingsCacheTest`,
  `NoConsolePrintingTest`, `support/LoginProtectionTestDefaults`
- Docs: `README.md`, `docs/2026-11-11-go-live-checklist.md`, `docs/2026-11-19-logging.md`,
  `docs/2026-11-11-login-protection.md`, `docs/2026-11-19-t0-4-site-settings-api-contract.md`,
  `docs/2026-11-20-site-settings.md`
