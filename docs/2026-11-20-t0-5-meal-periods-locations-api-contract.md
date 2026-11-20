# Meal periods and locations API contract (T0-5a)

Branch `feature/t0-5a-meal-periods-locations` (from `feature/t0-4a-site-settings`; V114). Status:
**draft for the frontend.**

Today meal periods (Breakfast, Lunch, All Day) come from migrations V17/V28 and the API is
read-only. T0-5a lets an admin create, edit, deactivate and activate them, so a new customer can
set its own. Background: multi-tenancy readiness analysis (19 Nov 2026) sections A.1, C.8 and H.20.

- **Part 1, locations found by role:** postponed. Section 9 is a placeholder.
- **Part 2, editable meal periods:** sections 1–8.

With no change made, behaviour is as before: the seeded periods keep their names and times.

## 1. Conventions

- `/admin/**` endpoints are **ADMIN only**; anyone else gets 403 with an empty body.
  `GET /meal-periods` stays public.
- Times are site-local times of day (the site time zone, T0-2). In responses they are
  `"HH:mm:ss"` strings (`"07:00:00"`), as `GET /meal-periods` returns today. Requests accept
  `"HH:mm"` or `"HH:mm:ss"`; seconds must be `00`.
- A period is open from `startTime` to `endTime` **inclusive** (Lunch 12:00–14:30 is open at
  14:30:00 and closed at 14:30:01), as today.
- `changedAt` and `lastChange.at` are UTC instants (`2026-11-20T08:15:00Z`).
- Errors:
  - **Validation** (a single field's value): **400 field map**, e.g. `{"name": "Required."}`.
  - **Rules**: the usual body `{"error": "...", "code": "...", "details": {...}}`. The frontend
    branches on `code`.

## 2. The meal period

| Field | Type | Notes |
|---|---|---|
| `id` | number | |
| `name` | string | 1–50 characters, trimmed. Unique among **active** periods, ignoring case. |
| `startTime`, `endTime` | string or null | null only for the All Day period |
| `allDay` | boolean | true only for the All Day period (section 5) |
| `accepts` | `SET_MEALS` or `LIGHT_MEALS` | What may be planned or offered under it. Periods created through the API accept `SET_MEALS`; All Day accepts `LIGHT_MEALS`. Not editable. |
| `plannable` | boolean | `accepts == SET_MEALS` |
| `active` | boolean | Admin endpoints only |
| `sortOrder` | number | Admin endpoints only. 0–9999; lists are ordered by `sortOrder`, then `id`. |
| `lastChange` | object or null | Admin endpoints only. `{"action", "byName", "at"}`, the newest history entry. |

The seeded periods get the sort orders 10 (Breakfast), 20 (Lunch) and 30 (All Day).

## 3. Endpoints

### 3.1 GET /meal-periods (public, unchanged shape)

**Active** periods only, ordered by `sortOrder`, then `id`. The fields are the same as today:

```json
[
  {"id": 1, "name": "Breakfast", "startTime": "07:00:00", "endTime": "10:00:00", "allDay": false, "accepts": "SET_MEALS", "plannable": true},
  {"id": 2, "name": "Lunch", "startTime": "12:00:00", "endTime": "14:30:00", "allDay": false, "accepts": "SET_MEALS", "plannable": true},
  {"id": 3, "name": "All Day", "startTime": null, "endTime": null, "allDay": true, "accepts": "LIGHT_MEALS", "plannable": false}
]
```

What changes: an inactive period is no longer listed, and the order follows `sortOrder` (until a
period is added or reordered, the order is the same as today).

### 3.2 GET /admin/meal-periods

Every period, **including inactive ones**, ordered by `sortOrder`, then `id`.

```json
[
  {"id": 1, "name": "Breakfast", "startTime": "07:00:00", "endTime": "10:00:00", "allDay": false,
   "accepts": "SET_MEALS", "plannable": true, "active": true, "sortOrder": 10, "lastChange": null},
  {"id": 5, "name": "Supper", "startTime": "17:00:00", "endTime": "19:00:00", "allDay": false,
   "accepts": "SET_MEALS", "plannable": true, "active": false, "sortOrder": 40,
   "lastChange": {"action": "DEACTIVATE", "byName": "Canteen Admin", "at": "2026-11-20T08:15:00Z"}}
]
```

### 3.3 POST /admin/meal-periods

Creates a timed period that accepts set meals. It is active at once.

```json
{"name": "Supper", "startTime": "17:00", "endTime": "19:00", "sortOrder": 40}
```

`sortOrder` is optional; without it the period goes last (the highest `sortOrder` + 10).

| Result | Response |
|---|---|
| Created | 201, the period (admin shape) |
| Field errors | 400 field map (section 4.1) |
| Overlaps an active timed period | 409 `PERIOD_OVERLAP` |

### 3.4 PUT /admin/meal-periods/{id}

Replaces `name`, `startTime`, `endTime` and, if sent, `sortOrder` (omitted: unchanged). It works
on an inactive period too; the overlap and name rules then apply when it is activated.

```json
{"name": "Lunch", "startTime": "11:30", "endTime": "14:30", "sortOrder": 20}
```

For the All Day period, `startTime` and `endTime` must be null or omitted.

| Result | Response |
|---|---|
| Saved (or nothing changed: nothing recorded) | 200, the period |
| Field errors | 400 field map |
| Overlaps another active timed period | 409 `PERIOD_OVERLAP` |
| Times given for All Day | 409 `ALL_DAY_PROTECTED` |
| Unknown id | 404 `MEAL_PERIOD_NOT_FOUND` |

**A time change applies at once** (section 6). Shortening a period during service stops selling
its items at once; the screen should say so before saving.

### 3.5 POST /admin/meal-periods/{id}/deactivate and /activate

No body. Both return 200 and the period; repeating either changes and records nothing.

| Result | Response |
|---|---|
| Deactivating All Day | 409 `ALL_DAY_PROTECTED` |
| Deactivating a period with options or extras dated today or later | 409 `PERIOD_IN_USE` |
| Activating a period whose name an active period now has | 400 field map on `name` |
| Activating a period that overlaps an active one | 409 `PERIOD_OVERLAP` |
| Unknown id | 404 `MEAL_PERIOD_NOT_FOUND` |

There is no delete: periods are referenced by past options, extras, ingredient confirmations and
stock requests.

### 3.6 GET /admin/meal-periods/history

Every change, newest first. Insert-only: entries are never edited or removed.

Query: `periodId` (optional), `page` (from 0, default 0), `size` (default 50, at most 200).

```json
{
  "content": [
    {
      "id": 12,
      "periodId": 2,
      "action": "UPDATE",
      "before": {"name": "Lunch", "startTime": "12:00:00", "endTime": "14:30:00", "sortOrder": 20, "active": true},
      "after":  {"name": "Lunch", "startTime": "11:30:00", "endTime": "14:30:00", "sortOrder": 20, "active": true},
      "changedAt": "2026-11-20T08:15:00Z",
      "changedById": 1,
      "changedByName": "Canteen Admin"
    }
  ],
  "page": 0, "size": 50, "totalElements": 1, "totalPages": 1
}
```

- `action`: `CREATE` (`before` is null), `UPDATE`, `DEACTIVATE` or `ACTIVATE`.
- `changedByName` is a snapshot: it stays if the user is renamed or deleted.
- The seeded periods have no history until they are first changed.

## 4. Rules

### 4.1 Field validation (400 field map)

| Field | Message |
|---|---|
| `name` | "Required." / "At most 50 characters." / "Another active period is already called Lunch." |
| `startTime`, `endTime` | "Required." / "Use HH:mm." / "Seconds must be 00." |
| `endTime` | "Overnight periods are not supported; the end must be after the start." (also when equal) |
| `sortOrder` | "Must be between 0 and 9999." |
| (body) | `{"body": "Must be a JSON object."}` for a missing or non-object body |

Every bad field is listed at once.

### 4.2 No overlap

Active timed periods may not overlap. **Touching ends are allowed** (10:00–12:00 and 12:00–14:30);
both are open for the instant 12:00:00, which is harmless. All Day never overlaps anything.

```json
{"error": "Overlaps Lunch (12:00–14:30).", "code": "PERIOD_OVERLAP",
 "details": {"periodId": 2, "name": "Lunch", "startTime": "12:00:00", "endTime": "14:30:00"}}
```

### 4.3 Deactivating

Refused while any option or extra for the period is dated **today (the site's trading day) or
later**, so today's trading is never broken and no planned item is left unsellable:

```json
{"error": "Supper has 3 planned items from 21 Nov 2026. Move or remove them first.", "code": "PERIOD_IN_USE",
 "details": {"options": 2, "extras": 1, "fromDate": "2026-11-21"}}
```

Planned items can be moved to another period or deleted on the daily planning screen. Past items,
orders and reports keep the period.

An inactive period:

- is not open at any time (selling, `GET /menu/today`)
- is left out of `GET /meal-periods` and the offline snapshot
- can't be used for new plan items, or for moving an item to it (400 `MEAL_PERIOD_INACTIVE`,
  "Meal period Supper is inactive."); an item already under it keeps it
- stays in the report filters and in `GET /admin/meal-periods`

## 5. The All Day period

All Day is the one period with `allDay: true` and `accepts: LIGHT_MEALS`. It has no times (always
open). Each day's light meals are created under it automatically. Beverages have no period.

- **Allowed:** rename it (still unique among active periods) and change its `sortOrder`.
- **Refused, 409 `ALL_DAY_PROTECTED`:**
  - giving it times
  - deactivating it, ever, because light meals depend on it
- **Not possible through the API:**
  - creating a second All Day period (POST always creates a timed period)
  - changing `accepts`

## 6. Effects of a change

| Change | Takes effect |
|---|---|
| Times | **At once** for `GET /menu/today` (without `period`), new orders (400 "Meal option's meal period is not currently active.") and the admin view. Offline tills: their next snapshot. |
| Name | At once everywhere the name is shown, **including past reports and the dashboard** (they show the current name; section 7) |
| `sortOrder` | At once for `GET /meal-periods` and the admin list |
| Deactivate / activate | At once for the menu, ordering, planning and `GET /meal-periods`; offline tills at their next snapshot |

**Offline sales.** The `PERIOD_CLOSED` check at sync (O4) now uses the period's times **in force
at `soldAt`** (from the history), not the times at sync. A time change between a till's snapshot
and its sync no longer flags sales that were valid when made.

## 7. Reports

Reports find a sale's period through the daily option it was sold from, by **id**, so past sales
keep their period whatever changes later. They show the period's **current** name and sort by its
current start time. Renaming "Lunch" to "Midday" relabels past reports; the history records the
rename. The dashboard's sales-mix group key is derived from the name and changes with it.

## 8. Frontend notes

- **Never hard-code period names.** `GET /menu/today?period=`, the daily plan and the ingredient
  requirements take a period **name**. Take names from `GET /meal-periods`; a rename otherwise
  breaks the call. With an active and an inactive period sharing a name, the active one is used.
- Show inactive periods greyed out in the admin list, with Activate.
- Before saving a time change, warn that it applies to selling at once.

### Error codes

| Status | Code | When |
|---|---|---|
| 400 | (field map) | Section 4.1 |
| 403 | (empty) | Not an ADMIN |
| 404 | `MEAL_PERIOD_NOT_FOUND` | Unknown id |
| 400 | `MEAL_PERIOD_INACTIVE` | Planning a new item under an inactive period, or moving one to it (4.3) |
| 409 | `PERIOD_OVERLAP` | 4.2 |
| 409 | `PERIOD_IN_USE` | 4.3 |
| 409 | `ALL_DAY_PROTECTED` | Section 5 |

## 9. Locations found by role (Part 1, later)

Postponed. Today the code finds "Main Store" and "Kitchen" by name. A later step will give each
location a role so the names can change; its endpoints will be added here.
