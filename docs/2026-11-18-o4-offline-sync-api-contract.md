# Offline sync: API contract (O4)

How a POS till hands over the sales it made offline, how the server records them, and how managers review what needs attention. It builds on these contracts, which still apply:
- O3 offline foundation: `docs/2026-11-17-o3-offline-foundation-api-contract.md` (grant, snapshot, heartbeat, numbering)
- O2b receipt data: `docs/2026-11-17-o2b-receipt-data-api-contract.md` (VAT rule, receipt numbers)
- O2 tills, shifts and sessions: `docs/2026-11-17-o2-tills-shifts-api-contract.md`
- O1 resilience: `docs/2026-11-16-o1-resilience-api-contract.md` (`clientOrderId`)

**Status:** implemented on `feature/o4-offline-sync` (see `docs/2026-11-18-o4-offline-sync-change-report.md`).

## Contents

1. Principles
2. `POST /pos/offline/sales`
3. The sale payload
4. Results
5. How a sale is recorded
6. Exception codes
7. Changes to orders (`OrderDto`) and reprints
8. Exception review (admin endpoints)
9. Changes to existing endpoints and reports
10. Settings
11. Frontend checklist
12. Decisions

## 1. Principles

- **A completed offline sale is never rejected.** Money was taken, so the server records it and raises **exceptions** for anything that needs a manager's attention. The only refusals are authentication failures and payloads that can't be read.
- **The till's figures stand.** Prices, totals, VAT and numbers are recorded as charged and printed; differences from what the server would have done become exceptions.
- **The till authenticates, not the cashier.** Sync uses the till's cookie and the grant each sale was made under (O3). No session or bearer token is needed.
- **Offline sales are already collected.** They never reach the kitchen screen, bar screen or public board.
- **What the customer holds is what reprints show.** An offline sale's receipt number and collection number are the till's, everywhere.
- **Offline sales are plain sales.** No discount at sale and no complimentary meals offline (both need an online manager approval). The till doesn't offer them while offline.

## 2. `POST /pos/offline/sales`

Sends up to **50** offline sales, oldest first.

- **Authentication:** the till's `deviceToken` cookie only. A **revoked or expired** browser or till is accepted here (and only here); the sales are recorded with `DEVICE_REVOKED`. A missing or unknown cookie is refused.
- **Body:** `application/json`, at most 512 KB.
- Each sale is processed **on its own**, in the order given: one sale's problem never stops the rest.
- **Idempotent** on each sale's `clientOrderId`: sending a sale again returns the same order.
- When to call it: as soon as the till is back online, then until its queue is empty. Send a status report (O3 heartbeat) straight after each batch, with the new pending counts.

**Request:**

```json
{
  "grants": ["eyJraWQiOiJrMSIsImFsZyI6IkhTMjU2In0.eyJ0eXAiOiJvZmZsaW5lLWdyYW50Ii…"],
  "sales": [ { …one sale, section 3… } ]
}
```

| Field | Rule |
|---|---|
| `grants` | Required, 1 to 10 entries: the signed `grant` strings (O3 section 5) of every grant the batch's sales were made under, sent exactly as received. |
| `sales` | Required, 1 to 50 entries. |

**Responses:**

| Situation | Response |
|---|---|
| Processed (whatever each sale's result) | **200** `{"results": [...], "serverTimeUtc": "…Z"}`, one result per sale, in the same order |
| No cookie, or a cookie that matches no browser | 403 `DEVICE_NOT_REGISTERED` |
| Body unreadable, `grants` or `sales` missing or empty, more than 50 sales or 10 grants | 400 `{"error": "...", "code": "INVALID_BATCH"}` |
| Body over 512 KB | 413 |
| Not JSON | 415 |
| An unexpected server error | 500. The batch stops there; sales before it may already be recorded. Resend the batch: those come back as `DUPLICATE`. |

## 3. The sale payload

```json
{
  "clientOrderId": "3b8f1c2e-4a5d-4e6f-9a0b-1c2d3e4f5a6b",
  "grantId": "7c1e9a52-2f0b-4d0c-9a7e-3b1f6e2d4c10",
  "offlineSeq": 7,
  "offlineReceiptNumber": "T1-20261118-0007",
  "collectionNumber": "T1-7",
  "soldAt": "2026-11-18T10:42:15Z",
  "tillClockAtSendUtc": "2026-11-18T13:05:01Z",
  "shiftId": 412,
  "cashierId": 17,
  "unlockedOffline": false,
  "snapshotVersion": "9f2c4e1ab07d5c3e",
  "lines": [
    {"dailyMealOptionId": 801, "name": "Beef stew", "station": "KITCHEN", "quantity": 1,
     "unitPrice": 50.00, "lineTotal": 50.00,
     "extras": [{"dailyComponentStockId": 3102, "name": "Extra beef", "priceDelta": 15.00, "quantity": 1, "lineTotal": 15.00}]},
    {"dailyMealOptionId": 912, "name": "Still water 500 ml", "station": "NONE", "quantity": 2,
     "unitPrice": 20.00, "lineTotal": 40.00, "extras": []}
  ],
  "subtotal": 105.00,
  "total": 105.00,
  "vatRate": 0.1500,
  "vatIncludedAmount": 13.70,
  "payments": [
    {"method": "CASH", "amount": 105.00, "amountTendered": 200.00, "changeDue": 95.00}
  ]
}
```

| Field | Rule |
|---|---|
| `clientOrderId` | **Essential.** A UUID, made by the till when the sale was confirmed. If the sale was first tried online (O1) and its outcome was unknown, send the **same** one. |
| `grantId` | **Essential.** The grant the sale was made under; its signed string must be in `grants`. |
| `offlineSeq` | The sale's number for this till and trading day (O3 section 7), ≥ 1. |
| `offlineReceiptNumber` | As printed, e.g. `T1-20261118-0007`, at most 30 characters. |
| `collectionNumber` | As printed, e.g. `T1-7`, at most 20 characters. |
| `soldAt` | **Essential.** When the sale was confirmed: the till's clock corrected by its server offset, UTC. |
| `tillClockAtSendUtc` | The till's own clock (uncorrected) when it sent this batch, UTC. |
| `shiftId`, `cashierId` | As recorded on the till. Expected to match the grant. |
| `unlockedOffline` | `true` when the cashier unlocked the till offline (PIN checked on the till, O3 decision D13) before this sale. |
| `snapshotVersion` | The snapshot the till sold from (for support). |
| `lines` | **Essential:** at least one. `quantity` ≥ 1. `lineTotal` is `unitPrice × quantity`, without the extras (they have their own `lineTotal`). `dailyMealOptionId` may be null for an item the till can't match. `name` is what was printed. |
| `lines[].extras` | May be empty. Same rules. |
| `subtotal`, `total` | As charged, ≥ 0. |
| `vatRate`, `vatIncludedAmount` | As printed. |
| `payments` | As taken. `CASH`: `amount`, `amountTendered`, `changeDue`. `CARD`: `amount`, `cardReference`. `ACCOUNT`: `amount`, `accountId`. |

Amounts are numbers with at most 2 decimals; `vatRate` at most 4.

**What makes a sale INVALID** (section 4): a missing or malformed **essential** field (`clientOrderId`, `grantId`, `soldAt`, at least one line), a `grantId` with no matching entry in `grants`, a non-numeric or negative amount or quantity, or an unknown payment method. Everything else is recorded, with exceptions where needed.

## 4. Results

```json
{
  "results": [
    {"clientOrderId": "3b8f1c2e-…", "result": "CREATED", "orderId": 9301, "orderNumber": 58,
     "receiptNumber": "T1-20261118-0007", "collectionNumber": "T1-7", "exceptions": []},
    {"clientOrderId": "a17d…", "result": "CREATED_WITH_EXCEPTIONS", "orderId": 9302, "orderNumber": 59,
     "receiptNumber": "T1-20261118-0008", "collectionNumber": "T1-8",
     "exceptions": [{"code": "OVERSOLD", "severity": "REVIEW"}, {"code": "PERIOD_CLOSED", "severity": "INFO"}]},
    {"clientOrderId": "c2e9…", "result": "DUPLICATE", "orderId": 9288, "orderNumber": 51,
     "receiptNumber": "T1-20261118-0005", "collectionNumber": "T1-5", "exceptions": []},
    {"clientOrderId": null, "result": "INVALID", "error": "clientOrderId is required.", "exceptions": []},
    {"clientOrderId": "f00d…", "result": "REFUSED", "code": "GRANT_INVALID",
     "error": "The grant for this sale isn't valid.", "exceptions": []}
  ],
  "serverTimeUtc": "2026-11-18T13:05:01.412Z"
}
```

| `result` | Meaning | What the till does |
|---|---|---|
| `CREATED` | Recorded, nothing to review. | Remove it from the queue. |
| `CREATED_WITH_EXCEPTIONS` | Recorded; `exceptions` lists what managers will review. | Remove it from the queue. Nothing to tell the cashier. |
| `DUPLICATE` | This `clientOrderId` was already recorded (an earlier sync, or the online `POST /orders` whose outcome was unknown). The existing order is returned. When it's a sale first sent online (O1) that the till then printed offline, the order takes the slip's offline receipt and collection numbers (once), so reprints match the slip. | Remove it from the queue. |
| `INVALID` | The sale couldn't be read (section 3). `error` says why. Nothing was recorded. | **Keep it**, mark it for support, and continue with the rest. |
| `REFUSED` | `code` `GRANT_INVALID`: the grant's signature doesn't check out, or the grant belongs to another till. Nothing was recorded. | **Keep it**, mark it for support. |

`exceptions` on `DUPLICATE` is the exceptions raised by this call (only `DUPLICATE_DIFFERS` can be). `orderNumber` is the server's number for the day the sale is booked to; the till never prints it.

## 5. How a sale is recorded

In its own transaction, without any check against the server's clock (no meal-period, "today" or READY refusal):

| Item | Recorded as |
|---|---|
| Till | The grant's device. The cookie's till must be the grant's till (otherwise `REFUSED`). |
| Shift and cashier | The **grant's**, even when the payload says otherwise (`GRANT_MISMATCH`) and even when the shift has closed since (`LATE_AFTER_SHIFT_CLOSE`). |
| `soldAt` | As sent. `createdAt` stays the time the server recorded it. |
| Trading day | `soldAt`'s date in Africa/Johannesburg. If that day already has a Z, the sale is **booked** to the first later day without one (`LATE_AFTER_Z`); `bookedTradingDay` says so, and reports count it on that day. |
| `orderNumber` | The next number for the booked (or sale) day, as for online sales. |
| Receipt and collection numbers | `offlineReceiptNumber` and `collectionNumber` as printed. The pair (till, sale day, `offlineSeq`) is unique; a clash is still recorded, without its sequence number, and flagged `OFFLINE_NUMBER_CLASH`. The server's "highest number seen" (O3 `nextOfflineSeq`) is raised to `offlineSeq`. |
| Status | `COLLECTED` straight away. No station rows; nothing sent to the kitchen or bar screens or the public board. The status history gets one entry (to `COLLECTED`). |
| Lines and extras | As charged (name, quantity, unit price, line total). Unknown ids are recorded by name and price, without a menu link (`UNKNOWN_ITEM`). |
| Portions and extra buffers | Decremented by the quantities sold, **never below 0**: when there weren't enough, the count goes to 0 and the shortfall is recorded (`OVERSOLD`). |
| Light-meal ingredients | Consumed from the Kitchen as for online sales, dated `soldAt` (stock movements gain an "occurred at" time). |
| Totals | `subtotal`, `total` as charged. No discount: the pre-discount total and the original total are `total`. |
| VAT | Recomputed with the O2b rule on `total` at the rate in force, and stored. A different printed value or rate is flagged (`VAT_MISMATCH`). |
| Payments | As taken. A payment with an amount of 0 or less isn't stored (`PAYMENT_MISMATCH`). ACCOUNT payments have no approving manager yet (`ACCOUNT_APPROVAL_PENDING`). |

## 6. Exception codes

**REVIEW** exceptions need a manager; **INFO** exceptions are recorded for context and don't count as open work.

| Code | Severity | Raised when | `detail` |
|---|---|---|---|
| `ACCOUNT_APPROVAL_PENDING` | REVIEW | Every offline ACCOUNT payment (no manager approved it at the time). | `accountId`, `accountNumber`, `amount` |
| `ACCOUNT_OVER_CAP` | REVIEW | This account's ACCOUNT payments under the same grant, this one included, exceed `accountCap`. | `accountId`, `capTotal`, `cap` |
| `ACCOUNT_INACTIVE` | REVIEW | The account is inactive, or unknown (then recorded without an account). | `accountId`, `reason` (`INACTIVE` or `UNKNOWN`) |
| `PAYMENT_MISMATCH` | REVIEW | Payments don't add up to `total`; a payment of 0 or less; cash tendered less than the amount, or change that doesn't match. | `paymentsTotal`, `total`, `problem` |
| `TOTALS_MISMATCH` | REVIEW | Lines and extras don't add up to `subtotal`, or `subtotal` differs from `total`. | `linesTotal`, `subtotal`, `total` |
| `PRICE_MISMATCH` | REVIEW | A line's `unitPrice` (or an extra's `priceDelta`) differs from the server's price for that option (or extra) on that day. The till's price stands. | `lineIndex`, `optionId`, `charged`, `serverPrice` |
| `UNKNOWN_ITEM` | REVIEW | A line's option, or an extra's stock id, is missing or unknown. | `lineIndex`, `name`, `charged` |
| `OVERSOLD` | REVIEW | Portions or an extra's buffer didn't cover the quantity; recorded at 0. | `optionId` or `componentStockId`, `requested`, `shortfall` |
| `VAT_MISMATCH` | REVIEW | The printed `vatIncludedAmount` or `vatRate` differs from the server's. | `printedRate`, `printedVat`, `serverRate`, `serverVat` |
| `CARD_REFERENCE_MISSING` | REVIEW | A CARD payment without a slip reference. | `amount` |
| `LATE_AFTER_Z` | REVIEW | The sale's day already had a Z; booked to the next open day. | `saleDay`, `bookedDay` |
| `LATE_AFTER_SHIFT_CLOSE` | REVIEW | The grant's shift had closed before the sale was recorded; its cash shows as late cash (section 9). | `shiftId`, `closedAt` |
| `OUTSIDE_GRANT` | REVIEW | `soldAt` is before the grant was issued, after it expired, or after it was revoked. | `issuedAt`, `expiresAt`, `revokedAt`, `revokeReason` |
| `DEVICE_REVOKED` | REVIEW | The batch came from a revoked or expired browser or till. | `status` (`REVOKED` or `EXPIRED`) |
| `GRANT_MISMATCH` | REVIEW | The payload's `shiftId` or `cashierId` differs from the grant's; the grant's are used. | `sentShiftId`, `sentCashierId`, `grantShiftId`, `grantCashierId` |
| `DUPLICATE_DIFFERS` | REVIEW | A resend of a recorded `clientOrderId` with different totals, lines or payments. Raised once, on the existing order. | `differences` |
| `OFFLINE_NUMBER_CLASH` | REVIEW | Another sale already has this till, day and `offlineSeq`. | `offlineSeq`, `otherOrderId` |
| `CLOCK_SKEW` | REVIEW | `tillClockAtSendUtc` is more than 5 minutes from the server's time. | `skewMs` |
| `FUTURE_SALE` | REVIEW | `soldAt` is more than 1 minute after the server received the sale. | `soldAt`, `receivedAt` |
| `PERIOD_CLOSED` | INFO | A line's meal period wasn't open at `soldAt`. | `lineIndex`, `periodId` |
| `ITEM_UNAVAILABLE` | INFO | A line's option wasn't READY, was out today, or belongs to another day. | `lineIndex`, `optionId`, `reason` |
| `UNLOCKED_OFFLINE` | INFO | The till was unlocked offline before the sale. | (none) |
| `STOCK_AFTER_COUNT` | INFO | A light-meal ingredient used by the sale was counted in a stock take submitted after `soldAt`; that count didn't include this use. | `stockTakeId`, `ingredientId` |

A sale can have several exceptions. Each exception belongs to one order and keeps its trading day and till for filtering.

## 7. Changes to orders (`OrderDto`) and reprints

Every order response (`POST /orders`, `GET /orders/{id}`, reprints, the sync results' orders) gains:

| Field | Type | Meaning |
|---|---|---|
| `recordedOffline` | boolean | `true` for a sale recorded through sync. |
| `soldAt` | instant (`…Z`) or `null` | When an offline sale was made; `null` for online sales (use `createdAt`). |
| `collectionNumber` | text or `null` | The till's collection number for an offline sale. |
| `bookedTradingDay` | date or `null` | Set when a late sale was booked to a later day (`LATE_AFTER_Z`). |

For an offline sale, **`receiptNumber` is the till's offline receipt number** (e.g. `T1-20261118-0007`) in every response, reprints included, so a reprint matches the customer's slip. Online sales keep the O2b format. An online sale whose outcome was unknown and that the till then printed offline (sent again through sync, `DUPLICATE`) takes the slip's offline receipt and collection numbers, so its reprints match the slip; it stays an online sale (`recordedOffline` false).

## 8. Exception review (admin endpoints)

ADMIN only (401 without sign-in, 403 for other roles).

### 8.1 `GET /admin/sale-exceptions`

Query parameters, all optional: `status` (`OPEN` or `RESOLVED`; default `OPEN`), `severity` (`REVIEW` or `INFO`; default `REVIEW`), `code`, `tradingDay` (`yyyy-MM-dd`), `deviceId`, `page` (from 0), `size` (default 50, at most 200). Newest first.

```json
{
  "items": [
    {"id": 311, "code": "OVERSOLD", "severity": "REVIEW", "status": "OPEN",
     "tradingDay": "2026-11-18", "tillCode": "T1", "deviceId": "6f1c…",
     "order": {"id": 9302, "receiptNumber": "T1-20261118-0008", "collectionNumber": "T1-8", "soldAt": "2026-11-18T10:51:40Z",
               "total": 85.00, "cashierName": "Thandi Mokoena"},
     "detail": {"optionId": 801, "requested": 2, "shortfall": 1},
     "createdAt": "2026-11-18T13:05:01Z"}
  ],
  "page": 0, "size": 50, "totalItems": 1
}
```

### 8.2 `GET /admin/sale-exceptions/{id}`

One exception with the same fields plus the resolution (`resolution`, `resolutionNote`, `resolvedBy {id, name}`, `resolvedAt`) and the full order (`OrderDto`). 404 when unknown.

### 8.3 `POST /admin/sale-exceptions/{id}/resolve`

`{"note": "Counted; the extra portion came from the spare tray."}`: marks it `RESOLVED` with the note (required, at most 500 characters) and who resolved it.

| Situation | Response |
|---|---|
| Resolved | 200, the exception (8.2) |
| Note blank or too long | 400 `{"note": "..."}` |
| Already resolved | 409 `EXCEPTION_ALREADY_RESOLVED` |
| An `ACCOUNT_APPROVAL_PENDING` exception | 409 `USE_APPROVE_ACCOUNT` (use 8.4) |

Resolving doesn't change the sale. To reverse a sale, use the existing refund or void on the order.

### 8.4 `POST /admin/sale-exceptions/{id}/approve-account`

For `ACCOUNT_APPROVAL_PENDING` only, `{"note": "..."}` (optional, at most 500): records the manager on the ACCOUNT payment as its approver (as an online approval would have) and resolves the exception with resolution `ACCOUNT_APPROVED`. 409 `NOT_AN_ACCOUNT_APPROVAL` for any other code; 409 `EXCEPTION_ALREADY_RESOLVED` when done. To reject a charge instead, refund the order.

## 9. Changes to existing endpoints and reports

- **`GET /admin/tills`:** each till's `offline` gains `openExceptions`: its open REVIEW exceptions.
- **Dashboard, Attention section:** the number of open REVIEW exceptions, with a link to 8.1.
- **X and Z readings:**
  - Offline sales count on their trading day, or on `bookedTradingDay` when booked late; the sale hour is `soldAt`'s.
  - A new **Offline sales** section: count and total of offline sales, open REVIEW exceptions for the day.
  - **Late offline cash:** cash taken offline on a shift that had already closed shows on its own line ("Late offline cash after close: R x, n sales"), on that shift's cashier reading and in the day's Z. The closed shift's own figures (expected cash, variance) don't change.
  - Z definition **3**. Z readings already taken keep their snapshot.
  - Late offline cash is left out of the cash-ups reconciliation checks (Cash-up & Variance and Tender Summary), so they keep agreeing; it is shown on its own line instead.
- **Close Day:** open REVIEW exceptions for the day are a **warning**, not a blocker: they show in the X reading's checks and the Z's offline section.
- **Status (O3):** unchanged. The till reports its new pending count in the heartbeat it sends after each batch; `OFFLINE_SALES_PENDING` clears when it reports 0.

## 10. Settings

| Setting | Default | Meaning |
|---|---|---|
| `pos.offline.grant-previous-signing-key` (`POS_OFFLINE_GRANTPREVIOUSSIGNINGKEY`) | none | Optional. A previous signing key, used **only to verify** grants during sync, so the current key can be rotated without refusing sales made under older grants. Base64, at least 32 bytes. |
| `pos.offline.grant-previous-key-id` (`POS_OFFLINE_GRANTPREVIOUSKEYID`) | none | The `kid` of that key. Both or neither. |
| Batch limits | 50 sales, 10 grants, 512 KB | Fixed. |
| Thresholds | clock skew 5 minutes, future sale 1 minute | Fixed. |

## 11. Frontend checklist

- [ ] Send queued sales with `POST /pos/offline/sales`, oldest first, up to 50 at a time, with the signed grants they were made under.
- [ ] Remove `CREATED`, `CREATED_WITH_EXCEPTIONS` and `DUPLICATE` from the queue; keep `INVALID` and `REFUSED` and show them for support.
- [ ] After each batch, send a status report with the new pending counts.
- [ ] Use the same `clientOrderId` for a sale first tried online with an unknown outcome.
- [ ] Print `receiptNumber` and `collectionNumber`; for reprints of offline sales they come back unchanged.
- [ ] Don't offer discounts or complimentary meals while offline.
- [ ] Set `unlockedOffline` for sales made after an offline unlock.
- [ ] Manager screens: exception list and detail, resolve, approve account charges, counts in Tills and on the dashboard.

## 12. Decisions

**Approved (O4 prompt):** I (never reject a completed sale), G (till plus grant; revoked or expired credentials and grants accepted on sync only), A (account approvals pending; cap per account per grant), C (offline receipt and collection numbers on reprints), D (already collected, no screens), D4 (oversold accepted), D5 (the till's price stands), D6 (late after Z booked to the next open day), D8 (card reference), D9 (5-minute skew), H (duplicates of online sales).

**Approved with the plan:**
1. Reports count a late sale on `bookedTradingDay` (the report views change; online sales are unaffected).
2. Order lines and extras may have no menu link (UNKNOWN_ITEM).
3. Portions and buffers never go below 0; the shortfall is recorded.
4. Offline sales carry no discount or complimentary.
5. The batch carries the signed grants; a bad signature or another till's grant gives `REFUSED` for that sale only.
6. `unlockedOffline` on each sale.
7. Batch limit 50 sales (and 10 grants, 512 KB).
8. A number clash is recorded without its sequence number and flagged.
9. Late cash is shown on its own line; closed shift figures don't change.
10. Stock movements gain "occurred at"; stock-take variance unchanged, with `STOCK_AFTER_COUNT` for context.
11. `approve-account` is a separate action that records the approver.
12. Till status comes from the heartbeat after each batch.
13. Close Day warns, doesn't block, on open REVIEW exceptions.
14. A previous signing key may be configured for verification only.

**Added here (not in the prompt):** `TOTALS_MISMATCH` (lines don't add up to the total), `INVALID_BATCH`, `EXCEPTION_ALREADY_RESOLVED`, `USE_APPROVE_ACCOUNT` and `NOT_AN_ACCOUNT_APPROVAL`.
