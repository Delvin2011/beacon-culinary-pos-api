# Frontend prompt: WS1-1 Phase 2 (login protection)

Give this prompt to the frontend agent together with `docs/2026-11-11-login-protection-api-contract.md` from POS_BE (branch `feature/ws1-1-phase2-enforcement`). Text marked **[backend]** was added or corrected after checking the built backend.

---

## ROLE AND RULES

You are preparing the canteen POS frontend for WS1-1 Phase 2 (login protection enforcement). The backend will start returning HTTP 429 "too many attempts" responses, add a staff unlock action, and require 6-digit PINs.

Working rules (mandatory):

1. Step 1 is a READ-ONLY analysis. Change nothing until I approve your plan.
2. Show the diff before modifying any existing file and wait for confirmation.
3. Work on branch `feature/ws1-1-phase2-frontend`. Commit per step; do not push or merge.
4. Name new docs `YYYY-MM-DD-descriptive-name.md`. Summarise after each step; list every file created or modified at the end.
5. Build against the backend contract **`docs/2026-11-11-login-protection-api-contract.md`** (attached). It is authoritative. If something you need is not in it, list it as a question; do not guess.

## BACKEND CONTRACT (summary; the contract document wins on any difference)

- **Wrong credential:** unchanged. 401 with an empty body for password and PIN sign-in, and for a wrong old password on change-password. The manager PIN keeps its 401 body `{"error":"Invalid PIN."}`.
- **Blocked or locked:** 429, header `Retry-After` (seconds, exposed via CORS), and JSON body `{ code, message, retryAfterSeconds }`.
  - `ACCOUNT_LOCKED`: the account is locked. When `retryAfterSeconds` is null, a manager must unlock it; then there is **no** `Retry-After` header.
  - `RATE_LIMITED`: too many attempts. `retryAfterSeconds` is always a number for this code (handle null defensively anyway).
  - **[backend]** The body's `message` is generic and safe to show as is. You may show your own wording instead, but choose the text from `code` and `retryAfterSeconds` only, never from anything that could differ between a real and an unknown account.
- **[backend] Where 429 can appear:**
  - **Every** request under `/auth/**`: `/auth/login`, `/auth/pin-login`, `/auth/refresh` and `/auth/me`, because of a per-device/network burst limit.
  - `/admin/authorize` and `/admin/authorize-session`.
  - `/users/{id}/change-password`.
  - Nowhere else.
- **`/auth/refresh`:** returns **401** while the account is locked. Treat that as "session ended", as today. **[backend]** It can also return **429** (burst limit). That is **not** a logout and must not be retried in a loop.
- **[backend] Order of checks:** request validation runs before the lock check. A malformed request (for example a 5-digit PIN) gets **400**, never 429, except from the burst limit.
- **New:** `POST /admin/staff/{id}/unlock` (ADMIN). Optional body `{"scope": "PASSWORD" | "PIN" | "MANAGER_PIN" | "CHANGE_PASSWORD"}`; no body unlocks all four.
  - **[backend]** It returns the updated staff record (same shape as `GET /admin/staff/{id}`). Use it to refresh the row; no second request is needed.
  - Errors: 400 `{"scope":"…"}` for an unknown scope; 404 for an unknown staff member.
- **Staff records gain `locks`**: `locks.PASSWORD` and `locks.PIN` only, each `{locked, lockedUntil, requiresAdminUnlock}`.
  - **[backend]** `lockedUntil` is a UTC ISO instant (`…Z`); show it in local time. It is null when not locked, or when `requiresAdminUnlock` is true.
  - **[backend]** Short-term blocks (PIN per till, manager PIN, change password) are not listed. They end by themselves within 15 minutes, and an unlock ends them too.
  - **[backend]** `locks` is returned by `GET /admin/staff`, `GET /admin/staff/{id}`, `POST /admin/staff`, `PUT /admin/staff/{id}`, `POST /admin/staff/{id}/pin`, `POST /admin/staff/{id}/password` and the unlock endpoint.
  - **[backend]** Setting a new PIN or password for someone already unlocks the matching scope; show the returned `locks`.
- **PINs must be exactly 6 digits** for PIN login and manager-PIN approval; anything else gets 400 `{"pin":"PIN must be 6 digits"}`.
  - **[backend]** This applies **as soon as the backend is deployed, in every mode**, before enforcement is switched on. The 6-digit PIN change must ship with or before that backend release.

**[backend] Rollout:** the backend first runs in `monitor` mode. 429s and locks don't happen until the owner switches enforcement on, so the frontend can ship first. The staff page will show every lock as `false` until then.

## STEP 1: ANALYSE (read-only) AND REPORT

1. Framework, HTTP client and any interceptors: how 401 is handled today; anything that auto-retries, refreshes, or logs out on errors. **[backend]** Say specifically what the refresh-on-401 logic would do if `/auth/refresh` itself returned 429.
2. Every screen or component that calls `/auth/login`, `/auth/pin-login`, `/auth/refresh`, `/auth/me`, `/admin/authorize`, `/admin/authorize-session`, `/users/{id}/change-password`, or the staff management endpoints (`/admin/staff…`). Give file:line for each.
3. How errors are shown to users today: toasts, inline messages, dialogs.
4. The PIN pad and the manager-PIN input: where PIN length is set or validated (4–6 today?), and whether a 6-digit entry auto-submits.
5. The staff management screen: where a "Locked" badge and an "Unlock" button would go, and the permission check for admin-only actions.
6. Any place a 429 would be misread as "session expired", trigger a retry loop, or be swallowed by a generic error handler.
7. **[backend]** Any place that keeps or shows a PIN or password after a failed attempt, or logs request bodies. Credentials must not be logged.

## STEP 2: PLAN (after I approve the analysis)

### Shared 429 handling

- **A shared 429 handler:** read `body.code` and `body.retryAfterSeconds`; fall back to the `Retry-After` header; when neither gives a time, treat it as "no time".
- **Messages** (plain, never saying whether an account exists; identical for real and unknown accounts):
  - `RATE_LIMITED`: "Too many attempts. Try again in {mm:ss}."
  - `ACCOUNT_LOCKED` with a time: "This account is temporarily locked. Try again in {mm:ss} or ask a manager."
  - `ACCOUNT_LOCKED` without a time: "This account is locked. Ask a manager to unlock it."
  - **[backend]** If `retryAfterSeconds` is over an hour, show hours and minutes (`{h:mm:ss}`). Locks last 15 or 60 minutes.
- **Countdown:** a live countdown; **no automatic retry**. When the countdown ends, re-enable the control and let the user try again; don't re-send anything yourself.
- **[backend] What the countdown disables:**
  - **Password login:** the submit button for that sign-in attempt.
  - **PIN sign-in (till):** only the **selected cashier**, not the whole PIN pad. A block is per cashier and till, so another cashier must still be able to choose their tile and sign in. If that cashier also gets a 429 (for example the till's burst limit), show their own countdown.
  - **Manager-PIN approval popup:** the popup's submit for the **signed-in user** (the block is on the person entering manager PINs from this session). Close or cancel must still work.
  - **Change password:** that form's submit. Signing in is not affected.

### Where it applies

- Password login, PIN sign-in, change password.
- The manager-PIN approval popup in every place it appears: voids, refunds, discounts, complimentary meals, account payments, shift-close variance approval, and the management session (`/admin/authorize-session`). List each one in Step 1.
- `/auth/refresh` and `/auth/me`: on a 429, don't log out and don't retry automatically. Keep the current session; let the next user action try again after the wait, or show the countdown if the user is waiting on that call.
- **Do not treat 429 as a logout.** Keep the existing 401 handling, including refresh returning 401 (session ended), which now also happens while the account is locked.

### PIN inputs

- PIN pad and manager-PIN inputs accept exactly **6 digits**. Validate before sending; don't send shorter or longer PINs.
- Show the backend's 400 `{"pin":"PIN must be 6 digits"}` inline if it ever arrives.

### Staff management (admin only)

- **Badge:** a "Locked" badge per staff member, listing the scopes (`Password`, `PIN`) and, for each, "until {local time}" or "needs unlock" when `requiresAdminUnlock` is true.
- **Unlock button:** shown only to admins, calling `POST /admin/staff/{id}/unlock`, with a confirmation and a success message.
  - With no body, it unlocks everything. That's the recommended default for the button; a per-scope choice is optional.
  - Update the row from the response body.
  - Show 404 or 400 errors plainly.
- **PIN/password reset dialogs:** after a reset, show the returned `locks`. The reset already unlocks that scope.

### Tests

- The 429 handler: both codes; with and without a time; body versus header fallback; a malformed or empty body.
- The countdown, including the hours format, and re-enabling at zero.
- No automatic retry, on sign-in and on `/auth/refresh` (a 429 on refresh doesn't log out).
- PIN length validation: 5 and 7 digits, and non-digits, are refused client-side.
- PIN sign-in: a 429 for one cashier doesn't disable another cashier's tile.
- Unlock: button visibility (admin only) and the call (with and without scope); the row updates from the response.

## Questions to raise if they come up

- **[backend]** The contract doesn't define which 429 a till sees first when both the till's burst limit and a cashier's block apply. The frontend must not rely on telling them apart: both are `RATE_LIMITED` with a wait.

FIRST REPLY: the Step 1 analysis only. Do not change anything yet.
