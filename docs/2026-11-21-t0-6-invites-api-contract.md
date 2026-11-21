# T0-6a invite links and demo accounts: API contract

Branch `feature/infrastructureUpdatesBE`, migration V116. Runbook: `docs/2026-11-21-first-admin-and-invites.md`.

Staff set their own password and PIN from a single-use link instead of an admin choosing them. The customer's
first admin gets one from the operator's first-admin command; everyone else from an admin, on the staff screen.
The seeded demo accounts can't sign in where real data lives.

Errors use the usual shape `{error, code, details}`. Branch on `code` (and `details`), never on `error`.

## 1. The link

```
{base}/invite#t=<token>
```

- `token`: 43 characters, `A–Z a–z 0–9 - _` (256 random bits). Shown once, when issued; never stored (only its
  SHA-256) and never logged.
- `base`: `app.public-base-url` (env `APP_PUBLIC_BASE_URL`), the address staff open the frontend at. When it isn't
  set, the API returns `link: null` and the frontend builds the link from its own address.
- The token is after `#`, so the browser never sends it to any server: not to the frontend host, not to the API in
  a URL. **The invite page reads `location.hash`, removes it from the address bar
  (`history.replaceState`), and sends the token in a request body only.** Never put it in a path or query string:
  the API logs every request's path.
- Links last `auth.invites.expiry-hours` (site setting, default 72, 1–720). The first-admin link always lasts 72
  hours.
- One open link per person: issuing a new one replaces the old one.

## 2. Public endpoints (no sign-in)

Both are under the `/auth/**` burst limit and login protection (method `INVITE`, see section 5). Responses are
`Cache-Control: no-store`.

### POST /auth/invites/lookup

Request: `{"token": "..."}`

200:

```json
{
  "userName": "Thandi Mokoena",
  "role": "HEAD_CHEF",
  "roleLabel": "Head Chef",
  "purpose": "STAFF_SETUP",
  "password": "REQUIRED",
  "pin": "OPTIONAL",
  "signInWith": "PASSWORD",
  "email": "thandi@example.com",
  "expiresAt": "2026-11-24T10:15:00Z"
}
```

| Field | Values |
|---|---|
| `purpose` | `FIRST_ADMIN`, `STAFF_SETUP` (new person), `RESET` (an admin reset their sign-in) |
| `password`, `pin` | `REQUIRED` (must be set), `OPTIONAL` (may be set), `NONE` (not part of this link: hide the field) |
| `signInWith` | `PASSWORD` (sign in with email and password) or `PIN` (tap their tile, enter the PIN) |
| `email` | Only when `signInWith` is `PASSWORD` |

Rules shown next to the fields: password 6–25 characters; PIN exactly 6 digits.

### POST /auth/invites/accept

Request: `{"token": "...", "password": "...", "pin": "..."}`. Leave out (or null) what the link doesn't set.

200: `{"signInWith": "PIN", "email": null}`.

Accepting:
- sets the password and/or PIN, and ends any sign-in lock on them;
- **ends every session the person has** (they are signed out everywhere; reason `CREDENTIALS_CHANGED`);
- uses the link up;
- **never signs anyone in**: send them to the sign-in screen (`signInWith`, with `email` filled in).

### Refusals (both endpoints)

| Status | `code` | When | Show |
|---|---|---|---|
| 404 | `INVITE_INVALID` | Unknown or malformed token; the link was replaced or revoked; the person was deactivated or their role changed; a demo account | "This link isn't valid. Ask your manager for a new one." |
| 410 | `INVITE_EXPIRED` | Past `expiresAt` | "This link has expired. Ask your manager for a new one." |
| 410 | `INVITE_USED` | Already accepted | "This link has already been used." and a button to sign in |
| 400 | `INVITE_CREDENTIALS_INVALID` | `details.fields`: field name (`password`, `pin`) to message. The link still works | The message next to each field |
| 409 | `PIN_IN_USE` | An admin chose a PIN another admin uses (manager approval matches PINs across admins) | "Choose a different PIN." |
| 429 | `RATE_LIMITED` | Too many attempts (login protection body, `Retry-After`) | As for sign-in |

## 3. Admin endpoints (ADMIN only)

### POST /admin/staff/{id}/invite

Request: `{"purpose": "STAFF_SETUP" | "RESET", "credentials": "PASSWORD" | "PIN" | "BOTH"}`. `credentials` is
optional:
- `STAFF_SETUP`: every credential the role uses (Head Chef: both, the PIN optional).
- `RESET`: the credentials the role requires (Head Chef: password only). Ask for `BOTH` to reset the PIN too.

201 (shown once; the token is never returned again):

```json
{
  "inviteId": 41,
  "purpose": "RESET",
  "credentials": "PIN",
  "token": "q3V9...43 characters",
  "link": "https://pos.example.com/invite#t=q3V9...",
  "expiresAt": "2026-11-24T10:15:00Z"
}
```

Show the link with a copy button and "Send this privately (WhatsApp or SMS to their own phone). It works once and
expires {expiresAt}." Don't store it. Any open link for the person is replaced.

| Status | `code` | When |
|---|---|---|
| 400 | `INVITE_REQUEST_INVALID` | `details.fields`: `purpose` (not STAFF_SETUP or RESET), `credentials` (the role doesn't use it), `email` (a password link for someone without an email) |
| 403 | `REAUTH_REQUIRED` | The person is an ADMIN and you didn't sign in with your password in the last 30 minutes |
| 404 | (empty body) | No such person |
| 409 | `INVITE_SELF` | Yourself: change your own password from your profile |
| 409 | `INVITE_USER_INACTIVE` | Deactivated: activate them first |
| 409 | `INVITE_DEMO_ACCOUNT` | A seeded demo account |

### POST /admin/staff/{id}/invite/revoke

No body. 200 with the person (section 4). With no open link, nothing changes.

### POST /admin/staff with `setup`

`setup`: `SET_NOW` (default, as before: password and PIN in the request) or `INVITE` (no password or PIN; the
person sets them from a link). With `INVITE`:
- `password` and `pin` must be left out (400 with field messages otherwise); `email` is still required for roles
  that use a password;
- the response (201) is the person with `issuedInvite` (the same body as `POST /{id}/invite`), shown once;
- an ADMIN created this way needs a recent password sign-in (403 `REAUTH_REQUIRED`).

Recommended: make "Send them a link" the default on the create form.

## 4. Changes to the staff responses

Every person in `GET /admin/staff`, `GET /admin/staff/{id}` and the other staff responses gains:

```json
"invite": {
  "status": "PENDING",
  "purpose": "STAFF_SETUP",
  "credentials": "PIN",
  "createdAt": "2026-11-21T10:15:00Z",
  "expiresAt": "2026-11-24T10:15:00Z",
  "usedAt": null,
  "revokedAt": null
}
```

`status`: `NONE` (never had a link; the other fields are null), `PENDING`, `EXPIRED`, `USED`, `REVOKED` (revoked
or replaced). It describes the latest link. Suggested column: "Link sent, expires {expiresAt}" / "Link expired:
send a new one" / nothing for `USED` and `NONE`.

`issuedInvite` appears only on a create with `setup: INVITE`.

Other changes:
- Someone waiting for their link (an open link, same role) can be edited without supplying the credentials the
  link will set.
- Deactivating someone, or changing their role, revokes their open link.
- Setting a password or PIN directly (`POST /admin/staff/{id}/password`, `/pin`) works as before.
- `POST /admin/staff/{id}/unlock` with `scope`: `INVITE` is not a scope (still PASSWORD, PIN, MANAGER_PIN,
  CHANGE_PASSWORD).

## 5. Login protection

| | |
|---|---|
| Method | `INVITE` (`login_attempts.method`), every lookup and accept. `identifier` is `invite:<id>`, or null for an unknown token. The token is never recorded |
| Outcomes | Unknown token: `UNKNOWN_USER` (a failure). Expired, used, revoked, bad password or PIN: `VALIDATION` (not a failure). Demo account: `DEMO_ACCOUNT` (not a failure). Done: `SUCCESS` |
| Rule `INVITE_IP` | Per client IP: by default 10 failures in 15 minutes (`security.login-protection.invite-ip.max-failures`, `.window`). Always logs `LOGIN_PROTECTION_ALERT type=INVITE_IP`; blocks (429) in `enforce` mode only |
| Account state | `INVITE` never writes `user_auth_state` |

## 6. Demo accounts

The eight seeded accounts (`*@canteen.local`, V13, V14, V32, V69, V89, V98) are marked `users.is_demo`.

**The guard** is on under the `prod` profile whatever the configuration says, and elsewhere when
`security.demo-accounts.block-sign-in` is true (env `SECURITY_DEMOACCOUNTS_BLOCKSIGNIN`, default false). While on:

| Way in | Result for a demo account |
|---|---|
| `POST /auth/login`, `POST /auth/pin-login` | 401, as a wrong credential (refused before the credential check) |
| `POST /admin/authorize`, `/authorize-session` | A PIN matching a demo admin approves nothing (as a wrong PIN) |
| `POST /auth/refresh` | 401: an existing session stops at its next refresh |
| `POST /users/{id}/change-password` | Refused, as a wrong old password |
| Invite lookup and accept | 404 `INVITE_INVALID` |
| `GET /users/login-tiles`, `/users/cashiers` | Left out |

Each refusal is recorded as outcome `DEMO_ACCOUNT` (not a failure towards lockout) and logged as
`LOGIN_PROTECTION_ALERT type=DEMO_ACCOUNT_REFUSED path=<PASSWORD|PIN|MANAGER_PIN|REFRESH|CHANGE_PASSWORD|INVITE>
user=<id>`. At startup the app logs how many demo accounts are active: with the guard on, a WARN
`LOGIN_PROTECTION_ALERT type=DEMO_ACCOUNTS_ACTIVE count=N guard=on`.

Sending a link to a demo account is always refused (`INVITE_DEMO_ACCOUNT`).

**Why block, not refuse to start:** deactivating the demo accounts without SQL needs a real admin, who is set up
through an invite, which needs the app running. Once the first admin has signed in, they deactivate the demo
accounts on the staff screen; the startup WARN stops when none is active.

## 7. Frontend summary

- New public page `/invite`: read the token from `#t=`, clear the hash, `lookup`, show the fields per
  `password` / `pin`, `accept`, then go to the sign-in screen named by `signInWith`.
- Staff screen: "Send link" / "Reset sign-in by link" (`POST /{id}/invite`), "Revoke link", the `invite` status
  column, and `setup: INVITE` on create (recommended default).
- Handle `REAUTH_REQUIRED` as on the till screens (sign in again with the password, then retry).
