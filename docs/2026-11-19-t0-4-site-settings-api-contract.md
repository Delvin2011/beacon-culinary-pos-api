# Site settings API contract (T0-4a)

Branch `feature/t0-4a-site-settings`. Status: **agreed; the frontend builds against it.**

An admin changes the customer's own settings from the app. Values are stored in the customer's
database (`site_settings`, V113) and apply without a restart or redeploy. With nothing saved, every
setting has today's value (the YAML / environment default), so behaviour is unchanged.

Background: multi-tenancy readiness analysis (19 Nov 2026) sections A.2, B.3, B.4 and 4;
`docs/2026-11-20-site-settings.md` (customer setting vs entitlement vs platform setting).

## 1. Conventions

- Every endpoint is **ADMIN only** (the `/admin/**` rule). Anyone else: 403 with an empty body.
- A setting is addressed by its **key**, which is its configuration property name
  (`vat.standard-rate`). Keys contain dots; they are used as-is in paths.
- JSON value types: `DECIMAL` and `INTEGER` are JSON numbers, `BOOLEAN` is a JSON boolean, `DATE`
  (`2026-10-12`), `TIME` (`12:30`) and `TIME_RANGE` (`06:00-15:00`) are strings. Only a setting
  with `limits.nullable = true` takes `null`.
- Times in responses (`lastChange.at`, history `changedAt`) are UTC instants (`2026-11-19T08:15:00Z`).
- **Sensitive** settings need a password sign-in within the last 30 minutes, as for till
  management. Otherwise 403 `REAUTH_REQUIRED`.
- Errors other than validation use the usual body: `{"error": "...", "code": "...", "details": {...}}`.
  Validation errors are a **400 field map** keyed by setting key, as for receipt settings:
  `{"pos.session.idle-expiry-minutes": "Must be at least the idle lock time (5)."}`.

## 2. The settings

| Key | Type | Default (today) | Limits | Group | Sensitive |
|---|---|---|---|---|---|
| `vat.standard-rate` | DECIMAL | 0.15 | 0 to 0.9999, at most 4 decimals | `VAT` | **yes** |
| `reports.close-day.first-trading-day` | DATE, nullable | 2026-10-12 (`CLOSE_DAY_FIRST_TRADING_DAY`) | Section 4.2 | `END_OF_DAY` | **yes** |
| `dashboard.variance.threshold` | DECIMAL | 50.00 | 0 to 99 999 999.99, 2 decimals | `DASHBOARD` | no |
| `dashboard.voidRefund.watchPercent` | DECIMAL | 3.0 | 0 to 100, 2 decimals | `DASHBOARD` | no |
| `dashboard.slowMeal.thresholdPercent` | DECIMAL | 25 | 0 to 100, 2 decimals | `DASHBOARD` | no |
| `dashboard.slowMeal.afterTime` | TIME | `12:30` | `HH:mm` | `DASHBOARD` | no |
| `dashboard.openingHours` | TIME_RANGE | `06:00-15:00` | `HH:mm-HH:mm`, start before end | `DASHBOARD` | no |
| `board.done-display-minutes` | INTEGER | 10 | 0 to 1440 | `BOARD` | no |
| `pos.tills.require-registered` | BOOLEAN | false | | `TILLS` | **yes** |
| `pos.session.idle-lock-minutes` | INTEGER | 5 | 1 to 1440 | `SESSIONS` | no |
| `pos.session.idle-expiry-minutes` | INTEGER | 30 | 1 to 1440, at least idle-lock | `SESSIONS` | no |
| `pos.offline.max-offline-hours` | INTEGER | 6 | 1 to `pos.offline.grant-max-hours` (platform, 18) | `OFFLINE` | no |
| `pos.offline.warn-offline-hours` | INTEGER | 5 | 1 to less than max-offline-hours | `OFFLINE` | no |
| `pos.offline.accounts-enabled` | BOOLEAN | true | | `OFFLINE` | **yes** |
| `pos.offline.account-cap` | DECIMAL | 150.00 | 0 to 99 999 999.99, 2 decimals | `OFFLINE` | **yes** |

Defaults are whatever the server's configuration says; the table shows the shipped values. GET
always returns the actual default (`defaultValue`).

**Offline settings and the entitlement.** The four `OFFLINE` settings only matter when the
offline entitlement (`pos.offline.enabled`, platform) is on. The response carries
`offlineEntitled`; when it is false, each offline setting has `active: false` and
`inactiveReason: "OFFLINE_NOT_ENTITLED"`. They can still be edited (a value saved now applies when
offline is switched on). The UI should show them greyed with a note.

**Not here** (configuration, not customer settings): entitlements (`pos.tills.max-active`,
`pos.offline.enabled`, `dashboard.features.*`) and platform settings (secrets and keys, CORS,
login protection, device trust, `site.*` locale, offline grant timings, job switches,
`app.business.name`). The business name the customer sees is the receipt settings'
`businessName` (`/admin/receipt-settings`).

## 3. Endpoints

### 3.1 GET /admin/settings

Every setting, in the order of section 2.

**200:**

```json
{
  "offlineEntitled": false,
  "settings": [
    {
      "key": "vat.standard-rate",
      "value": 0.15,
      "defaultValue": 0.15,
      "isDefault": true,
      "type": "DECIMAL",
      "limits": {"min": 0, "max": 0.9999, "scale": 4, "nullable": false},
      "group": "VAT",
      "description": "Standard VAT rate, as a fraction (0.15 = 15%). Prices include VAT.",
      "sensitive": true,
      "active": true,
      "lastChange": null
    },
    {
      "key": "pos.session.idle-expiry-minutes",
      "value": 45,
      "defaultValue": 30,
      "isDefault": false,
      "type": "INTEGER",
      "limits": {"min": 1, "max": 1440, "scale": 0, "nullable": false},
      "group": "SESSIONS",
      "description": "A POS session with no activity for this long ends (server backstop).",
      "sensitive": false,
      "active": true,
      "lastChange": {"byName": "Canteen Admin", "at": "2026-11-19T08:15:00Z", "action": "SET"}
    },
    {
      "key": "pos.offline.account-cap",
      "value": 150.00,
      "defaultValue": 150.00,
      "isDefault": true,
      "type": "DECIMAL",
      "limits": {"min": 0, "max": 99999999.99, "scale": 2, "nullable": false},
      "group": "OFFLINE",
      "description": "Most that may be charged to one account, on one till, during one outage.",
      "sensitive": true,
      "active": false,
      "inactiveReason": "OFFLINE_NOT_ENTITLED",
      "lastChange": null
    }
  ]
}
```

- `limits.min` / `max` are omitted for BOOLEAN, DATE, TIME and TIME_RANGE.
- `lastChange` is the newest history entry for the key (also after a reset), or null.
- `inactiveReason` is present only when `active` is false.

### 3.2 PUT /admin/settings

A partial update: only the keys sent change.

```json
{"pos.session.idle-lock-minutes": 10, "pos.session.idle-expiry-minutes": 45}
```

- **All or nothing.** All values are validated together, including the cross-field rules (section
  4) on the values that would result. One error and nothing is saved.
- A value equal to the current value is accepted and records nothing (a setting at its default
  stays at its default).
- Changing a saved value back to the default value stores it like any other value: `isDefault`
  means "no saved value" and becomes true only after a reset (3.3).
- If any **sensitive** setting would change, the password sign-in check applies to the whole
  request.

| Result | Response |
|---|---|
| Saved | 200, the full GET body |
| Empty body or `{}` | 200, the full GET body, nothing recorded |
| Not a JSON object (an array, or invalid JSON) | 400 `{"body": "Must be a JSON object of settings, e.g. {\"board.done-display-minutes\": 5}."}` |
| Unknown key, wrong JSON type, out of limits, cross-field rule broken | 400 field map; every bad key listed |
| A sensitive setting would change, no password sign-in in the last 30 min | 403 `REAUTH_REQUIRED` |
| Not an ADMIN | 403 (empty) |

**400 example:**

```json
{
  "pos.session.idle-lock-minutes": "Must be at most the idle expiry time (30).",
  "pos.session.idle-expiry-minutes": "Must be at least the idle lock time (40).",
  "vat.standrad-rate": "Unknown setting."
}
```

**403 example:**

```json
{"error": "Sign in again with your password to change vat.standard-rate.", "code": "REAUTH_REQUIRED", "details": {"keys": ["vat.standard-rate"]}}
```

Validation (400) is checked before the sign-in check (403).

### 3.3 POST /admin/settings/{key}/reset

Removes the saved value; the setting goes back to the default. No body.

| Result | Response |
|---|---|
| Reset (or already at the default: nothing recorded) | 200, the full GET body |
| The default would break a cross-field rule with another saved value (e.g. resetting idle expiry to 30 while idle lock is saved as 40) | 400 field map naming both keys |
| Sensitive setting, no recent password sign-in | 403 `REAUTH_REQUIRED` |
| Unknown key | 404 `UNKNOWN_SETTING` |

`POST /admin/settings/vat.standard-rate/reset`

### 3.4 GET /admin/settings/history

Every change, newest first. Insert-only: entries are never edited or removed.

Query: `key` (optional, exact key), `page` (from 0, default 0; a negative page is read as 0),
`size` (default 50; at most 200, larger values are reduced to 200).
An unknown `key` is not an error (empty page).

**200:**

```json
{
  "content": [
    {
      "id": 42,
      "key": "pos.session.idle-expiry-minutes",
      "action": "SET",
      "oldValue": 30,
      "oldIsDefault": true,
      "newValue": 45,
      "newIsDefault": false,
      "changedAt": "2026-11-19T08:15:00Z",
      "changedById": 1,
      "changedByName": "Canteen Admin"
    }
  ],
  "page": 0,
  "size": 50,
  "totalElements": 1,
  "totalPages": 1
}
```

- `action`: `SET` (PUT) or `RESET`.
- Values are typed as in section 2 (the effective value before and after).
- `changedByName` is a snapshot: it stays if the user is renamed or deleted.

### 3.5 Existing read endpoints (unchanged)

`GET /admin/settings/vat` (`{"standardRate": 0.15}`) and `GET /admin/settings/close-day`
(`{"firstTradingDay": "2026-10-12"}`) stay and now return the effective value.

## 4. Rules between settings

### 4.1 Cross-field rules

Checked on the values that would result from the request (saved + sent + defaults):

| Rule | Keys named in the 400 |
|---|---|
| idle-expiry-minutes ≥ idle-lock-minutes | both |
| warn-offline-hours < max-offline-hours | both |
| max-offline-hours ≤ `pos.offline.grant-max-hours` (platform) | max-offline-hours |
| opening hours: start before end | `dashboard.openingHours` |

### 4.2 First trading day

What it does: Z numbering starts on this day. Days before it are outside the end-of-day process:
Close Day refuses them (`BEFORE_FIRST_TRADING_DAY`), they don't block later days
(`PREVIOUS_DAY_NOT_CLOSED` only counts days from this one), and their shifts aren't signed off or
listed as unsigned. Z and end-of-day statements print "Z numbering starts …". Empty means every day
is in the process.

A change is refused (400 on the key) when:

1. **It is after the earliest Z reading's trading day**, which would put an existing Z outside the
   process. Message: "Must be on or before 12 Oct 2026, the first day with a Z reading."
2. **It is before the trading day of the earliest order**, or empty while orders exist.
   Message: "Must be on or after 12 Oct 2026, the first day with sales."
3. **A Z reading exists, and a day with shifts but
   no Z would fall on or after the new value and before the earliest Z.** Such a day would become
   one that must be closed, yet it comes before Z 1: every later Close Day would be blocked by
   `PREVIOUS_DAY_NOT_CLOSED`. Message: "Must be after 10 Oct 2026, a trading day before the first Z
   reading that was never closed."

With no orders and no Z readings, any date (or empty) is accepted. A future date is accepted
(set before go-live). It is sensitive (password sign-in) and recorded in the history like every
setting.

### 4.3 Single-value limits

As in section 2. Messages name the limit, e.g. "Must be between 0 and 0.9999 with at most 4
decimals."

## 5. Effects of a change

"This server" means the app instance that saved; other instances pick a change up within
**30 seconds** (their settings cache lifetime).

| Setting | Takes effect | Notes |
|---|---|---|
| `vat.standard-rate` | Next order created; next GRV line | Existing orders and GRV lines keep the rate stored on them (O2b). |
| | Offline tills: their next snapshot (`GET /pos/offline/snapshot`) | Sales made offline are stored with the VAT the till printed; see 5.1. |
| `reports.close-day.first-trading-day` | Next Close Day, sign-off, report or dashboard request | Stored Z readings are unchanged. |
| `pos.tills.require-registered` | Next PIN sign-in; next shift open, sale or adjustment | A cashier already signed in on an unregistered browser is refused at their next POS write (403 `DEVICE_NOT_REGISTERED`). Till status shows the new flag. |
| `pos.session.idle-lock-minutes` | When the till next reads till status (`GET /pos/till-status`) | The server doesn't enforce it; the POS app locks. |
| `pos.session.idle-expiry-minutes` | Next POS request; the minute sweep | Lowering it can end sessions already idle longer than the new value (401 `SESSION_ENDED`, reason `IDLE`). |
| `pos.offline.max-offline-hours`, `warn-offline-hours` | Next grant, snapshot or till status | A till already offline keeps the values it was given. |
| `pos.offline.accounts-enabled` | Next grant and snapshot | Stored on the grant (5.2). |
| `pos.offline.account-cap` | Next grant and snapshot | Stored on the grant (5.2). |
| `board.done-display-minutes` | Next board fetch (`GET /public/board/today`) and next order event on the stream | Orders already on a board keep the `removeAt` they were sent. |
| Dashboard thresholds and hours | Next dashboard request | This server clears its dashboard cache at once; other instances within 30 s plus the section's own refresh time. |

### 5.1 VAT on offline sales

A sale made offline is stored with the **VAT rate and amount the till printed** (the customer's
receipt). At sync, the server works out the rate in force at the sale's `soldAt` from the
settings history (the value after the last change before `soldAt`; with no change before it, the
value before the first change after it; with no changes at all, the current value). It raises
`VAT_MISMATCH` (for review) only when the printed rate differs from that rate, or the printed
amount differs from the VAT rule applied to the sale total at that rate.

- `VAT_MISMATCH` details: `printedRate`, `printedVat`, `serverRate` (now: the rate in force at
  `soldAt`), `serverVat` (that rate applied to the total).
- If the till sent no rate or amount, or one that can't be stored (rate outside 0 to 0.9999 or
  more than 4 decimals, amount negative, more than 2 decimals or more than the total), the server's
  values are stored and `VAT_MISMATCH` is raised when a value was sent.
- So a VAT change between a till's last snapshot and its sale no longer stores the sale at the
  new rate.

### 5.2 Offline accounts: the grant's values

The grant row stores `accounts_enabled` and `account_cap` as given to the till when the grant was
issued. At sync, `ACCOUNT_OVER_CAP` is checked against **the grant's cap**, not the current
setting. A grant issued with accounts off has no cap: an ACCOUNT payment synced under it (which the till
shouldn't have taken) is flagged `ACCOUNT_OVER_CAP` with `cap: 0`. Grants issued before V113 have no
stored values and use the current setting, as before.

## 6. Business name on reports

`app.business.name` stays a platform setting and its default becomes **blank**. Statement reports
(X, Z, Cashier reading) print the receipt settings' `businessName`, else `app.business.name`, else
**no business name**: `meta.business` and the "Business" header item are left out.
Onboarding must set the receipt settings' business name.

## 7. Error codes

| Status | Code | When |
|---|---|---|
| 400 | (field map) | Validation, section 4 |
| 403 | `REAUTH_REQUIRED` | A sensitive setting would change without a recent password sign-in (`details.keys`) |
| 403 | (empty) | Not an ADMIN |
| 404 | `UNKNOWN_SETTING` | Reset of an unknown key |
