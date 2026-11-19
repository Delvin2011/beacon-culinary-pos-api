# O4 offline selling: frontend prompt

The prompt for the frontend's O4 work (selling offline, the outbox and sync, the exception review screen). It's the reviewed version: it follows `docs/2026-11-18-o4-offline-sync-api-contract.md` (O4) and `docs/2026-11-17-o3-offline-foundation-api-contract.md` (O3). Paste everything inside the block below.

```
ROLE AND RULES
You are implementing O4 (selling offline, the outbox and sync, and the exception review screen) in
the canteen POS frontend, on top of O3. Read first: the O3 and O4 backend contracts
(docs/2026-11-17-o3-offline-foundation-api-contract.md and docs/2026-11-18-o4-offline-sync-api-contract.md;
I will give them to you), the O1 pending-sale design, the O2b receipt and kitchen-ticket components,
and the O2b VAT rule.

Working rules (mandatory):
1. PLAN FIRST: files to create/modify, the local order model, the outbox design, behaviour
   changes, tests and risks. Then STOP and wait for approval.
2. Show the diff before modifying any existing file and wait for confirmation.
3. Branch `feature/o4-offline-selling-frontend` from main if O3 is merged, otherwise from the O3
   branch (say which). Commit per step; do not push or merge.
4. New docs named YYYY-MM-DD-descriptive-name.md. Summarise after each step; list every file
   created or modified at the end.
5. Branch on error codes and result values only. List anything missing from the contract as a
   question; do not guess.

APPROVED DECISIONS
A   Offline account sales up to accountCap per account for this grant (from the grant and
    snapshot); switchable (accountsEnabled). Offline account sales need no manager PIN.
B   After reconnecting, the cashier signs in again if the server ended their session. The outbox
    syncs regardless (sync needs only the till cookie and the grants).
C   Offline receipt number T1-YYYYMMDD-NNNN and collection number T1-N (printed large). Reprints
    show these numbers (the server returns them as receiptNumber and collectionNumber).
D   A kitchen ticket is printed for every offline sale with kitchen items (as O2b); the order never
    reaches the kitchen screen.
D2  No change of cashier offline. D3: discounts, complimentary, voids, refunds, readings and shift
    close are online only.
D4  Stop at the local portion count. D7: 6-hour limit, warning at 5. D8: card slip reference
    required offline.
H   An online sale stuck in "Checking the sale…" when the till goes offline moves into the outbox
    with the SAME clientOrderId.
I   Nothing in the outbox is ever deleted before the server has confirmed it.

TASKS
1. Entering offline selling
   - Allowed when O3's readiness holds and the connection indicator says offline. Record
     offlineSince at the first moment offline.
   - The banner shows elapsed time and waiting sales. At warnOfflineHours: a warning. At
     maxOfflineHours: no new sales ("Offline limit reached. Reconnect to keep selling.").
   - Also stop new sales when the grant's expiresAt has passed (by server time), and at midnight
     (Africa/Johannesburg, by server time): the snapshot is for its own trading day only (O3 decision
     F), so after midnight the till isn't ready until it's back online. Show the reason.

2. Building the sale locally
   - From the snapshot: options of the period chosen by the till's clock plus the server offset,
     beverages and extras, with snapshot prices. Only options with status READY and not outToday.
   - Money as integer cents throughout; no floating-point arithmetic.
   - Line totals as the contract defines them (O4 section 3): a line's lineTotal is unitPrice ×
     quantity WITHOUT its extras; each extra has its own lineTotal (priceDelta × quantity); subtotal
     and total are the sum of both. (Folding extras into the line raises TOTALS_MISMATCH.)
   - Port the O2b VAT rule exactly: vatIncluded = round(totalCents × rate ÷ (1 + rate)) half up, with
     integer maths on the 4-decimal rate (snapshot vatRate). Test with every example in the O2b
     contract table.
   - Local portion and extra counters per option: decrement on sale; at zero, the tile shows sold
     out (D4).
   - Not available offline, each with a short reason on the button: discount, complimentary,
     manager-PIN actions, voids and refunds, shift reading, shift close, cashier change.

3. Payments offline
   - CASH: amountTendered ≥ amount, and changeDue exactly amountTendered − amount.
   - CARD: slip reference required (at most 50 characters).
   - ACCOUNT (when accountsEnabled): accounts from the snapshot. A local ledger per account per
     grant; refuse beyond the cap with "Account limit for offline sales reached (R{accountCap})."
     using the value from the grant or snapshot (never hard-coded).
   - The same split rules as online (at most 2 payments, ACCOUNT not combined).

4. Recording and printing (the order matters)
   - Assign offlineSeq (the larger of the local counter and the grant's nextOfflineSeq, per till per
     trading day). Build offlineReceiptNumber (at most 30 characters) and collectionNumber (at most
     20).
   - Record, per sale, everything the sync payload needs (O4 contract section 3): clientOrderId (new
     UUID), grantId, offlineSeq, offlineReceiptNumber, collectionNumber, soldAt (the till's clock
     corrected by the server offset, UTC), shiftId and cashierId (from the grant), unlockedOffline
     (true when the till was unlocked offline with the PIN check value since its last online
     sign-in, O3 D13), snapshotVersion, lines (dailyMealOptionId, name, station, quantity, unitPrice,
     lineTotal, extras with dailyComponentStockId, name, priceDelta, quantity, lineTotal), subtotal,
     total, vatRate, vatIncludedAmount, payments.
   - In ONE IndexedDB transaction: write the sale to the outbox (status PENDING), bump the counter,
     and update the portion counters and the account ledger.
   - Only then show the completion screen and print the receipt and kitchen ticket from the local
     order (O2b components). The receipt is marked "Offline sale"; there is no VAT line unless
     showVatLine; the local VAT value is used.
   - "Print again" buttons as online.

5. Stuck online sale (H)
   - When the till goes offline with an O1 pending sale whose outcome is unknown, convert it into an
     outbox entry with the same clientOrderId and its original confirm time as soldAt. Assign it an
     offline receipt and collection number and print it as an offline sale.
   - Its kitchen ticket is marked "Was pending online: check the kitchen screen before preparing":
     the online sale may have reached the kitchen already.
   - If sync later returns DUPLICATE for it (the online sale had gone through), the server keeps its
     order and stores the slip's offline receipt and collection numbers on it, so the order's
     receiptNumber and collectionNumber (and reprints) match the slip. Store the result as SYNCED like
     any other. The server flags DUPLICATE_DIFFERS only for real differences (totals, lines,
     payments).

6. Sync (O4 contract sections 2 to 4)
   - When online: POST /pos/offline/sales with {"grants": [...], "sales": [...]}. "grants" holds the
     signed grant string (the `grant` field of the O3 grant response, exactly as received) of every
     grant the batch's sales were made under; each sale names its grantId. Keep every grant string
     until all its sales are synced, even after a newer grant replaces it.
   - Send the outbox in soldAt order, in batches of at most 50 sales and 10 grants (512 KB), with
     back-off. tillClockAtSendUtc is the till's own, uncorrected clock at the moment of sending.
   - Results, per sale:
     - CREATED, CREATED_WITH_EXCEPTIONS, DUPLICATE: mark the entry SYNCED and store orderId,
       orderNumber, receiptNumber, collectionNumber and exceptions.
     - INVALID: keep it, flagged for a manager, with the server's error. Don't resend it
       automatically.
     - REFUSED (code GRANT_INVALID: the grant's signature failed or it belongs to another till):
       keep it, flagged for support. Don't resend it automatically.
   - Whole-batch responses:
     - 403 DEVICE_NOT_REGISTERED (unknown or missing cookie): stop, show a manager alert, keep the
       outbox. A revoked till is still accepted (its sales are flagged on the server), so keep
       syncing after a revoke.
     - 400 INVALID_BATCH, 413, 415: a bug in what the till sent; stop and alert.
     - 500 or a network failure: back off and resend the same batch (sales already recorded come
       back as DUPLICATE).
   - Keep synced entries for 7 days for lookup, then delete them. Never delete or alter an unsynced
     entry. Show progress ("Sending 12 offline sales…").
   - Straight after each batch, send the O3 status report (heartbeat) with the new pendingCount,
     pending per shift and trading day, oldestPendingSoldAt, lastSyncAt and offlineSince, so the
     server's OFFLINE_SALES_PENDING clears as soon as the outbox is empty.

7. Shift close and clean-up
   - Shift close is disabled while the outbox has entries for this shift ("3 offline sales still to
     send"). Still handle the server's 409 OFFLINE_SALES_PENDING.
   - After the shift closes and its outbox is empty: delete the ledger, the unlock value and the
     snapshot's accounts.
   - If the till is found revoked: keep the outbox and keep syncing (the server accepts it, flagged);
     delete everything else.

8. Today's orders while offline: show this till's offline sales from the outbox, marked "Offline,
   not yet sent" or "Sent".

9. Admin: "Sale exceptions" page (ADMIN), at /sale-exceptions (the dashboard's Attention item
   already links there)
   - List (GET /admin/sale-exceptions): defaults to status OPEN and severity REVIEW; filters status,
     severity, code, tradingDay, deviceId (till); paging (page, size up to 200, totalItems).
   - Detail (GET /admin/sale-exceptions/{id}) with the full order.
   - Resolve (POST …/resolve {note}, note required, at most 500 characters).
   - Approve account charges (POST …/approve-account {note?}): the only way to close
     ACCOUNT_APPROVAL_PENDING; it records the manager as the approver. To reject a charge, refund the
     order.
   - Errors: 409 USE_APPROVE_ACCOUNT, NOT_AN_ACCOUNT_APPROVAL, EXCEPTION_ALREADY_RESOLVED; 400
     {"note": "..."}; 404.
   - Open-count badge in the menu (GET /admin/sale-exceptions?size=1, read totalItems) and per till
     in the Tills view (offline.openExceptions).
   - Order views: mark offline orders (recordedOffline), show soldAt (UTC with Z; show local time),
     collectionNumber and, when set, bookedTradingDay ("booked to {day}").
   - Reports: the X and Z gain an "offline" section and an "offline-exceptions" check (WARN). If
     report sections are rendered generically, nothing to do; otherwise render them.

10. Outage drill checklist: docs/YYYY-MM-DD-offline-outage-drill.md
   - On a real registered till: get ready; pull the network cable; sell cash, card and account;
     reload the browser; lock and unlock; print; hit the 5-hour warning (with a test setting);
     reconnect; watch the sync; check the orders, exceptions, cash-up and Z; repeat with 2 tills.

TESTS
- Unit: cents arithmetic; line totals without extras; the VAT rule against the contract's examples;
  numbering persistence across a reload and with a higher grant value; the ledger cap (from
  accountCap); portion counters; cash change.
- Outbox: atomic write before printing; survives a reload; nothing deleted before confirmation;
  synced kept 7 days; INVALID and REFUSED kept and not resent.
- Sync: the batch carries the grants; order, batching (50 / 10 grants), back-off; each result
  (CREATED, CREATED_WITH_EXCEPTIONS, DUPLICATE, INVALID, REFUSED); a 500 resends the same batch;
  403 stops sync and keeps the outbox; a heartbeat follows each batch.
- Stuck online sale converted with the same clientOrderId; its kitchen ticket marked; DUPLICATE
  stored as SYNCED with the slip's numbers.
- unlockedOffline set after an offline unlock.
- Disabled actions offline; shift close blocked while entries remain; a 409 OFFLINE_SALES_PENDING
  handled.
- Stops: the 6-hour limit, the grant's expiry and midnight, each with a fake clock.
- Playwright: go offline mid-shift; sell cash, card and account; reload offline; lock and unlock
  offline; reconnect; outbox drained; orders show as sent.
- Exceptions page: list, filter, resolve, approve an account charge, the 409 codes; ADMIN only.
- offlineEnabled=false: behaviour identical to today.

FIRST REPLY: the plan only.
```
