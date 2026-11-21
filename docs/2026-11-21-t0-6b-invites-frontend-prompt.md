# T0-6b invite page and staff invite actions: frontend prompt

Prompt for the frontend work that builds on T0-6a (backend branch `feature/infrastructureUpdatesBE`, V116).
Contract: `docs/2026-11-21-t0-6-invites-api-contract.md`. Runbook: `docs/2026-11-21-first-admin-and-invites.md`.

```text
ROLE AND RULES
You are implementing T0-6b in the canteen POS frontend: the page an invite link opens, and invite actions on
the staff screen. Build against docs/2026-11-21-t0-6-invites-api-contract.md (I will give it to you). The
backend is on branch feature/infrastructureUpdatesBE (V116).

Working rules (mandatory):
1. PLAN FIRST: files to create/modify, routes, behaviour, tests and risks. Then STOP and wait for approval.
2. Show the diff before modifying any existing file and wait for confirmation.
3. Branch `feature/t0-6b-invites-frontend` from main. Commit per step; do not push or merge.
4. New docs named YYYY-MM-DD-descriptive-name.md. Summarise after each step; list every file created or
   modified at the end.
5. Branch on error codes (and details.fields) only, never on message text. List anything missing from the
   contract as a question.

THE TOKEN (applies everywhere)
- The link is {origin}/invite#t=<token>: the token is after '#', so no server ever receives it in a URL.
  Never put it in a path or query string.
- Never store it (no localStorage, sessionStorage, IndexedDB, cookies, or a persisted state store), never log
  it (console, error reporting, analytics), and send it only in the bodies of POST /auth/invites/lookup and
  POST /auth/invites/accept.
- Error reporting and analytics must not record the URL hash or these request bodies on the invite page.
- The service worker must not cache or intercept /invite or /auth/invites/*.

TASKS
1. The invite page (public route /invite; not on the POS shell)
   - On load: read t from location.hash, keep it in memory only, and immediately remove the hash
     (history.replaceState) so it isn't left in the address bar or history. No token: show "This link isn't
     valid."
   - POST /auth/invites/lookup {token}. Use a plain HTTP client: no bearer token, no refresh-on-401 or
     redirect handling. Show "Welcome, {userName}. Set up your {roleLabel} sign-in."
   - Fields from the response: password and pin are REQUIRED, OPTIONAL (may be left empty) or NONE (hidden).
     Password 6–25 characters with a confirm field; PIN exactly 6 digits, entered twice. Check the confirmations
     in the browser; show details.fields messages from INVITE_CREDENTIALS_INVALID under each field.
     PIN_IN_USE: "Choose a different PIN." under the PIN.
   - POST /auth/invites/accept {token, password?, pin?}. It never signs anyone in. Success: "You're all set.
     Sign in with your new details." with a button chosen by signInWith: PASSWORD → password sign-in with
     the returned email filled in; PIN → the PIN screen for the role (CASHIER: POS; KITCHEN: kitchen display;
     BARISTA: bar display).
   - INVITE_INVALID: "This link isn't valid. Ask a manager for a new one." INVITE_EXPIRED: "This link has
     expired. Ask a manager for a new one." INVITE_USED: "This link has already been used." with a sign-in
     button. 429: "Too many attempts. Try again in a few minutes." (Retry-After when present).
   - Show expiresAt in local time.

2. Staff screen (ADMIN only)
   - Per person, from invite.status: NONE (nothing), PENDING ("Link sent, expires {expiresAt}"), EXPIRED
     ("Link expired"), USED (nothing), REVOKED ("Link revoked").
   - "Send set-up link" (purpose STAFF_SETUP) and "Reset sign-in by link" (purpose RESET) via
     POST /admin/staff/{id}/invite. For a role with an optional PIN (Head Chef), offer "also reset the PIN"
     (credentials BOTH); otherwise leave credentials out.
   - The link dialog (shown once): the link (response link, or origin + "/invite#t=" + token when link is null),
     Copy, a QR code generated in the browser by a bundled library (never a hosted QR service), and "This link
     works once and expires at {expiresAt}. Send it privately to the person." Closing the dialog discards the
     token; it can't be shown again (send a new link instead).
   - "Revoke link" for PENDING or EXPIRED: POST /admin/staff/{id}/invite/revoke; refresh the person from the
     response.
   - REAUTH_REQUIRED (any link for another ADMIN, including creating an admin by invite): the existing password
     re-sign-in flow, then retry.
   - Errors: INVITE_REQUEST_INVALID (details.fields: purpose, credentials, email), INVITE_SELF ("Change your own
     password or PIN from your profile."), INVITE_USER_INACTIVE ("Activate them first."),
     INVITE_DEMO_ACCOUNT ("Demo accounts can't get a link.").
   - Create staff: a choice "Send them a link" (setup: INVITE, the default) or "Set password/PIN now"
     (setup: SET_NOW, as today). With INVITE, hide password and PIN; email is still required for roles that
     sign in with a password. Show the response's issuedInvite in the link dialog.

3. Sign-in screens: no change. A demo account is refused exactly like a wrong credential (401), on purpose:
   there is no code to show a different message.

TESTS
- Invite page: password + PIN (admin); PIN only (cashier); password with optional PIN left empty (head chef);
  mismatched confirmations; details.fields errors; PIN_IN_USE; invalid, expired and used links; 429; no token
  in the hash. The hash is cleared before the first call; the token is in no storage, no console output and no
  request other than the two invite POSTs; no bearer token is sent.
- Staff screen: each status; send set-up and reset links; the dialog shows the link once; copy; QR rendered
  with no network request; fallback link when link is null; revoke; REAUTH_REQUIRED then retry; each error code;
  create with "send a link"; the screen and actions hidden for non-ADMIN.
- The service worker doesn't cache or intercept /invite or /auth/invites/*.

QUESTIONS ALREADY KNOWN (answer in your plan)
- Branch: the backend endpoints exist only on feature/infrastructureUpdatesBE. Confirm whether to branch from
  main (and mock the API) or follow the infrastructure branch.
- Which existing routes are the password sign-in, POS, kitchen display and bar display screens.

FIRST REPLY: the plan only.
```

## Changes from the first draft

- Route `/invite#t=<token>` instead of `/invite/{token}`: a token in the path reaches the frontend host's logs and
  `Referer` headers.
- The link check is `POST /auth/invites/lookup` (token in the body), not a GET.
- Task 3 (a "This account is disabled" message for demo accounts) dropped: the API answers a demo account exactly
  like a wrong credential, on purpose, so there is no code to branch on.
- The sign-in button follows `signInWith` and the role, not "admins vs cashiers".
- Added: `OPTIONAL` fields, the `REVOKED` status, `REAUTH_REQUIRED` for any link to another admin, every error
  code, the `link: null` fallback, no app auth client on the invite page, a QR code generated locally, nothing
  capturing the token, the reset `credentials` option, and the branch question.
