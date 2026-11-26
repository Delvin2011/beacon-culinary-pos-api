# First admin and invite links: runbook

How a customer's first administrator is created, how staff get their sign-in from a link, and how the seeded demo
accounts are retired. API: `docs/2026-11-21-t0-6-invites-api-contract.md`. Introduced in T0-6a (V116).

No password, PIN, token or link is ever written in a ticket, chat log, email thread or this document.

## 1. What exists

- **Invite link**: `{frontend}/invite#t=<token>`. Single use, 72 hours by default (site setting
  `auth.invites.expiry-hours`). The person sets their own password and/or PIN; nobody else ever knows them. Only
  the token's SHA-256 is stored; the token is shown once and never logged.
- **First-admin command**: run by the operator (OptimumSCS) against the customer's database. Creates the first
  ADMIN with no credentials, and prints their link to the operator's terminal.
- **Demo accounts**: the eight `*@canteen.local` accounts seeded by the migrations, marked `users.is_demo`. Under the
  `prod` profile they can't sign in by any method (section 5).

## 2. Before you start

For a **new customer**, `scripts/new-customer-database.ps1` sets up the database from the baseline and then runs
the first-admin command in one go: see `docs/2026-11-22-new-customer-database.md`. The steps below are for running
the first-admin command on its own (an existing database, or a rerun when the link expired).

- The app has started once against the customer's database (it applies the migrations, V116 included).
- T1-7: links from the staff screen are on the customer's primary host (multi mode), or built by the frontend from
  its own address (single mode). There is no base-URL setting on the app.
- **The app runs with `SPRING_PROFILES_ACTIVE=prod`** (go-live checklist item 10). The startup log shows either
  `LOGIN_PROTECTION_ALERT type=DEMO_ACCOUNTS_ACTIVE count=N guard=on` or `Demo accounts: none active; demo guard
  on`. If it says `guard off`, stop: the prod profile isn't active.
- You have the app jar of the deployed version (`target/beacon-culinary-api-<version>.jar` from the build), Java 17, and network access to the database (an Azure SQL firewall
  rule for your workstation's address, removed afterwards).
- **Run the command from your own workstation, not from the Azure container's console**: the console's output is
  collected into the logs, and the link would end up there.
- **No PowerShell transcript**: the link is printed to the window, and a transcript would keep a copy. Don't run it
  under `Start-Transcript`; where transcription is switched on by group policy, delete that run's transcript file
  afterwards (the new-customer script warns when the policy is on). Clear the window (`Clear-Host`) when done.

## 3. Create the first admin

1. Agree with the customer who the first admin is (name and email). The email is their sign-in name.
2. In a terminal on your workstation, set the database connection for this session only (don't put it in a file
   or your shell history: type the password at a prompt, e.g. PowerShell `Read-Host -AsSecureString`, or use your
   password manager's CLI):

   ```
   SPRING_DATASOURCE_URL=jdbc:sqlserver://<server>.database.windows.net:1433;databaseName=<db>;encrypt=true
   SPRING_DATASOURCE_USERNAME=<operator login>
   SPRING_DATASOURCE_PASSWORD=<from the password manager>
   ```

3. Run:

   ```
   java -cp beacon-culinary-api-<version>.jar \
        -Dloader.main=com.beaconculinary.api.ops.FirstAdminCommand \
        org.springframework.boot.loader.launch.PropertiesLauncher \
        --name "Thandi Mokoena" --email thandi@customer.co.za \
        --base-url https://pos.customer.co.za
   ```

   `--base-url` is the customer's address (its primary host). Without it, the command prints the bare token and you
   build `{frontend}/invite#t=<token>` yourself.

4. Output (exit code 0):

   ```
   First admin ready: user 57, invite 12, expires 2026-11-24T10:15:03.123456 UTC.
   Send this link to thandi@customer.co.za over a private channel. It works once:
   https://pos.customer.co.za/invite#t=...
   This is the only time the link is shown. Lost it? Run this again for the same email.
   ```

5. Send the link to the person over a private channel (WhatsApp or SMS to their own phone, or read it out on a
   call). Don't email it to a shared mailbox and don't paste it into a ticket. Close the terminal.
6. The person opens the link, sets a password (6–25 characters) and a 6-digit PIN, and is sent to the sign-in
   screen. Confirm with them that they can sign in.
7. Remove your firewall rule.

| Exit | Meaning | What to do |
|---|---|---|
| 0 | Done | Send the link |
| 2 | Usage: a missing or wrong option | Read the message; fix the command |
| 3 | Refused: database not at V116; an admin is already set up; the email belongs to another account; another run in progress | See below |
| 4 | Failed: database error | Check the connection settings and firewall rule; the message gives the SQL state |

**The link expired or was lost.** Run the same command again with the same email. While that admin hasn't set a
password, it replaces their link (the old one stops working). No second account is created.

**"Refused: N active admin(s) already set up".** The customer already has a working admin: they add more admins
from the staff screen. Only if **none of them can sign in** (all locked out, left the company): confirm this with
the customer's authorised contact, then run with `--force --reason "<why, and the ticket number>"`. The reason
is stored on the invite (`user_invites.operator_forced`, `operator_reason`). Review forced runs with:

```sql
SELECT i.[id], u.[email], i.[created_at], i.[operator_reason]
FROM [user_invites] i JOIN [users] u ON u.[id] = i.[user_id]
WHERE i.[operator_forced] = 1 ORDER BY i.[created_at] DESC;
```

**"that email belongs to an existing account".** Use another email, or ask an admin to manage that account from
the staff screen.

## 4. Staff links (customer admins)

From the staff screen:

- **New person**: create them with "Send them a link" (`setup: INVITE`). No password or PIN is typed. Copy the link
  shown once and send it privately to the person.
- **Forgotten password or PIN**: "Reset sign-in by link" (`purpose: RESET`). The person sets new ones; every
  session they have ends; their sign-in locks are cleared.
- **Another admin's link** needs you to have signed in with your password in the last 30 minutes.
- **Revoke** a link sent to the wrong person. Deactivating someone or changing their role revokes their link too.
- The staff list shows each person's latest link: pending (with its expiry), expired, used or revoked.

Setting a password or PIN directly still works, but then someone other than the person knows it; use links.

## 5. Retiring the demo accounts

The migrations seed eight demo accounts with one shared, publicly documented credential (go-live checklist
item 1). Under the `prod` profile **they can't be used**: password and PIN sign-in, manager-PIN approval, refresh
of an existing session, change password and invite links all refuse them, and they are left off the login tiles.
Each refusal logs `LOGIN_PROTECTION_ALERT type=DEMO_ACCOUNT_REFUSED`.

They still exist until someone deactivates them:

1. After the first admin has signed in, they open the staff screen and **deactivate every demo account** (the
   `@canteen.local` emails: Canteen Admin, Canteen Cashier, Cashier B, Kitchen Station, Stock Clerk, Stock Admin,
   Head Chef, Canteen Barista).
2. Restart, or wait for the next deploy: the startup line becomes `Demo accounts: none active; demo guard on`.

The demo admin can't be the "last active admin" blocking this: the first admin is active.

Outside production (dev, test), the guard is off unless `SECURITY_DEMOACCOUNTS_BLOCKSIGNIN=true`, so developers and
the test suite keep using the demo accounts.

## 6. Monitoring

| Log line | Meaning |
|---|---|
| `LOGIN_PROTECTION_ALERT type=DEMO_ACCOUNTS_ACTIVE count=N guard=on` (startup) | Demo accounts still active in production: deactivate them (section 5) |
| `LOGIN_PROTECTION_ALERT type=DEMO_ACCOUNT_REFUSED path=...` | Someone tried a demo account. Expected rarely (an old bookmark); repeated: someone is trying the published credential |
| `LOGIN_PROTECTION_ALERT type=INVITE_IP` | Many unknown invite tokens from one address: someone is guessing. Blocked (429) in enforce mode |
| `Invite issued; invite=… user=… purpose=… by=…` | An admin sent a link (no token in the line) |
| `Invite accepted; invite=… user=… sessionsEnded=…` | A link was used |

Invite attempts are in `login_attempts` with `method = 'INVITE'` (identifier `invite:<id>`).
