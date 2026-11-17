# O3 offline foundation: frontend prompt

The prompt for the frontend's O3 work (service worker, local storage, snapshot and grant, readiness, offline lock and unlock, heartbeat). It's the reviewed version: it follows `docs/2026-11-17-o3-offline-foundation-api-contract.md`. Paste everything inside the block below.

```
ROLE AND RULES
You are implementing O3 (offline foundation) in the canteen POS frontend. O1, O2 and O2b are merged
into main. Read first: your offline analysis of 16 Nov 2026, the O1 and O2 frontend plans, and the
backend contract docs/2026-11-17-o3-offline-foundation-api-contract.md (I will give it to you). The
backend is on feature/o3-offline-foundation and not merged yet: build against the contract.

Working rules (mandatory):
1. PLAN FIRST: files to create/modify, the service-worker approach, the IndexedDB schema, behaviour
   changes, tests and risks. Then STOP and wait for approval.
2. Show the diff before modifying any existing file and wait for confirmation.
3. Branch `feature/o3-offline-foundation-frontend` from main. Commit per step; do not push or merge.
4. New docs named YYYY-MM-DD-descriptive-name.md. Summarise after each step; list every file created
   or modified at the end.
5. Branch on error code only. List anything missing from the contract as a question; do not guess.

GOAL OF O3
The POS survives an outage: it loads without the internet, keeps the cashier on the till, and is
READY to sell offline. Selling offline itself is O4: in O3, Confirm still waits for the connection
(as O1 does today).
Everything is behind the server's offlineEnabled flag (GET /pos/till-status). With it off, the app
behaves exactly as today, except for the status heartbeat (task 7), which runs regardless.

APPROVED DECISIONS
F   Sell offline only from today's snapshot; refresh it at sign-in, at shift open, every 5 minutes
    online and on reconnect. Never clear it because a refresh failed.
G   Offline data only on registered tills, for the open shift's owner, with a valid grant.
D13 Offline unlock: at a successful online PIN sign-in by the shift owner on a registered till, keep
    a check value for that cashier only, for the length of the shift. Offline: 5 tries, then a
    manager is needed once back online.

ERROR CODES (contract section 4)
OFFLINE_DISABLED, DEVICE_NOT_REGISTERED, SESSION_ENDED (with details.reason), POS_SESSION_REQUIRED,
NO_OPEN_SHIFT, NOT_SHIFT_OWNER, OFFLINE_SALES_PENDING (shift close; can't occur in O3, handle it
anyway), REAUTH_REQUIRED. 400 field maps for invalid bodies.

TASKS
1. Service worker
   - Propose: Serwist (or similar) vs hand-written. Explain how it fits Next.js 14 standalone on
     Azure App Service.
   - Precache the POS shell only: /pos, /pos/login, /pos/shift-open, their route data, and the
     /_next/static files they need. Serve POS navigations from the cache when offline.
   - Never cache API responses (including /health and /pos/till-status) or back-office routes.
   - Updates: a new version waits and activates only on the sign-in screen, when no sale is pending
     and (from O4) the outbox is empty. Versioned per build.
   - Check and document the cache headers Azure sends for the service worker file and the HTML. The
     SW file must not be cached long.

2. Local storage (IndexedDB)
   - Propose the library (e.g. idb) and a versioned schema: grant, snapshot (with its ETag),
     unlockVerifier, meta (offlineSince, serverTimeOffsetMs, lastSnapshotAt, lastStatusAt). Reserve
     stores for O4: outbox, accountLedger, offlineSeq.
   - Request persistent storage (navigator.storage.persist) and report the result on the device page.
   - Handle storage being unavailable: offline readiness is then "not ready", and the till works
     online as today.

3. Snapshot and grant
   - Snapshot (contract section 8): GET /pos/offline/snapshot at sign-in, at shift open, every 5
     minutes while online and on reconnect, with If-None-Match. 304 keeps the stored one. Needs a POS
     session on this till but no shift. Never fetch it while offlineEnabled is false. Keep the last
     good one; only today's (snapshot.tradingDay = today in Africa/Johannesburg by server time)
     counts. Sell only options with status READY (read-only in O3).
   - Grant (contract sections 5 and 6): POST /pos/offline/grant
     - after shift open;
     - after every online sign-in or resume by the owner of this till's open shift;
     - when less than maxOfflineHours is left before expiresAt.
     Store the response as is (the `grant` string is opaque: never parse it).
     - NOT_SHIFT_OWNER (e.g. an ADMIN covering): quietly "not ready", no error shown.
     - NO_OPEN_SHIFT, DEVICE_NOT_REGISTERED, OFFLINE_DISABLED: delete the grant and the unlock value.
   - A grant is valid only if its shiftId is the open shift (till-status shift.shiftId), its
     cashierId is the signed-in user (and the shift's owner), and expiresAt hasn't passed (by server
     time). Don't check deviceId: the till can't learn its own (till-status doesn't return it), and
     this browser's storage only ever holds grants issued to its own cookie. A revoked till shows as
     till-status registered=false: delete everything then. Store deviceId as received.
   - Delete the grant and unlock value when: the shift closes (including a force close seen via
     till-status); the till is found revoked; a different cashier opens a shift; offlineEnabled
     becomes false. Keep the snapshot (it holds no secrets) but delete its accounts list at shift
     close and everything at till revoke.
   - Server time offset: from the most recent serverTimeUtc you received (GET /health, the grant,
     the snapshot or the status reply). Use it for "today", grant expiry and, in O4, timestamps.
   - nextOfflineSeq and tradingDay from the grant: store them in the reserved offlineSeq store for O4.

4. Offline readiness and banner
   - "Ready for offline" when ALL hold: offlineEnabled; registered till; open shift owned by the
     signed-in cashier; valid grant (task 3); today's snapshot; storage available; and, once
     offline, less than maxOfflineHours since offlineSince.
   - Show a small "Offline ready" marker in the POS header when ready (online).
   - When the connection indicator (O1) goes offline:
     - ready: a banner "Offline mode · {elapsed}", with a warning from warnOfflineHours;
     - not ready: "Offline. This till can't sell offline: {reason}" (one reason per rule above,
       including "offline for more than {maxOfflineHours} hours").
   - Record offlineSince when going offline, and clear it after reconnecting (and, from O4, after
     the outbox is empty).

5. Staying on the till offline
   - A reload or app restart while offline lands on the POS (from the cache) with the cashier still
     shown. Restore the session locally without calling the server; don't redirect to sign-in or
     shift open.
   - The menu shows from the snapshot (read-only in O3).
   - Today's orders list and other server-only views say "Not available offline".
   - Back online: the access token will have expired, and after more than 30 minutes without POS
     activity the server has ended the session (refresh fails with SESSION_ENDED, reason IDLE). That
     is expected: show the sign-in screen, where the owner signs back in (resume). The grant is not
     affected.

6. Lock and unlock offline (D13)
   - At a successful ONLINE PIN sign-in by the shift owner on a registered till with offline
     enabled, derive a check value from the PIN with WebCrypto PBKDF2-SHA256: a random 16-byte salt,
     and iterations tuned so a check takes about 0.3–0.5 s on a till. Measure and report.
   - Store {userId, shiftId, salt, iterations, hash, createdAt}. Never store or log the PIN. Never
     send the value to the server. Delete it with the grant (task 3).
   - Offline Lock till (and offline auto-lock) lock locally; queue POST /pos/lock as O2 does (it
     returns 204 even for a session that has already ended).
   - Offline sign-in screen: only the shift owner's tile is usable (cashierId and cashierName from
     the grant); their PIN is checked against the stored value. 5 wrong tries disable offline unlock
     until the till is online again. Others see "Offline: only {owner} can unlock this till."
   - Back online, the normal server sign-in applies again.
   - Note in the risks: a 6-digit PIN check value can be brute-forced by anyone who can read the
     till's storage; the limits are its lifetime (one shift) and, in O4, flagging offline-unlocked
     sales.

7. Status heartbeat (contract section 9)
   - POST /pos/offline/status, authenticated by the till cookie only (no bearer token needed):
     - every 60 s while online;
     - immediately after reconnecting;
     - (from O4) after each sync.
     Send it whenever the app is open on a registered till: also on the sign-in and locked screens,
     and also when offlineEnabled is false. A till silent for more than 10 minutes shows as UNKNOWN
     to managers.
   - Body: {pendingCount: 0, pending: [], tillClockUtc, offlineSince?, lastSyncAt?, appVersion
     (max 50 chars)} (pendingCount and pending stay 0 and [] until O4). Use the reply's serverTimeUtc
     for the time offset; show clockSkewMs on the device page and warn when it's large.
   - DEVICE_NOT_REGISTERED: stop sending until the till is registered again.

8. Manager screens
   - Tills view (GET /admin/tills): show offline.state (NEVER_REPORTED, OK, PENDING, UNKNOWN),
     pendingCount, lastReportAt and clockSkewMs per till.
   - The Close Day OFFLINE_SALES_PENDING blocker and its override are O4 (no till reports pending
     sales in O3); show the blocker's message if it ever appears.

9. Docs: docs/YYYY-MM-DD-offline-foundation.md: what is stored on the till, when it's deleted,
   readiness rules, and how to check a till is offline-ready.

TESTS
- The service worker precaches only POS routes, and API calls are never served from it (unit, or
  Playwright with offline emulation).
- Playwright: load /pos, go offline (context.setOffline), reload: the POS opens with the cashier and
  the snapshot menu; no redirect.
- Snapshot refresh: ETag 304; a failed refresh keeps the old snapshot; yesterday's snapshot makes the
  till "not ready"; never fetched with offlineEnabled false.
- Grant lifecycle: fetched after shift open and owner sign-in; renewed near expiry; NOT_SHIFT_OWNER
  is quiet; deleted at shift close, force close, revoke, another cashier's shift and offline turned off.
- Readiness reasons, one test each, including "offline longer than maxOfflineHours".
- Offline unlock: the owner unlocks with the right PIN; wrong PIN 5 times disables it; others are
  refused; the PIN isn't stored.
- Reconnect after a long outage: SESSION_ENDED (IDLE) leads to sign-in, with the grant kept.
- Heartbeat: sent every 60 s and on reconnect, also when signed out and when offlineEnabled is false;
  body valid against the contract.
- offlineEnabled=false: nothing changes compared with today, apart from the heartbeat.

FIRST REPLY: the plan only.
```
