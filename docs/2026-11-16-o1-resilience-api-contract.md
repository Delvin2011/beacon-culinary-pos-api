# O1 resilience: API contract

Status: **sales and adjustments idempotency are built** (branch `feature/o1-resilience`, commit `f274c9e`). The order-number, `createdAt` and `/health` sections describe what Steps 2, 4 and 5 deliver; they are marked as such until those steps are committed.

Audience: the POS till frontend. Everything here is additive: a till that sends none of the new fields behaves exactly as before.

---

## 1. Duplicate-safe sales: `POST /orders`

### Request

One new optional field:

| Field | Type | Rule |
|---|---|---|
| `clientOrderId` | UUID string (e.g. `"3f2c7a1e-9b4d-4c62-a1f0-5d8e2b7c9a10"`) | Optional today. Generate one per sale on the till (`crypto.randomUUID()`). Not a UUID → 400. |

All other fields are unchanged.

```json
{
  "clientOrderId": "3f2c7a1e-9b4d-4c62-a1f0-5d8e2b7c9a10",
  "lines": [{ "dailyMealOptionId": 412, "quantity": 2, "extras": [{ "dailyComponentStockId": 88, "quantity": 1 }] }],
  "payments": [{ "method": "CASH", "amount": 115.00, "amountTendered": 200.00 }]
}
```

### Responses

| Situation | Status | Body | Headers |
|---|---|---|---|
| New sale created | **201** | `OrderDto` | `Location: /orders/{id}` |
| Same `clientOrderId`, same payload, same cashier (or an ADMIN) | **200** | `OrderDto` of the existing sale | `Location`, `Idempotent-Replay: true` |
| Same `clientOrderId`, different payload | **409** | `{ "error": "...", "code": "IDEMPOTENCY_CONFLICT" }` | |
| Same `clientOrderId`, sent by a different cashier (not ADMIN) | **409** | `IDEMPOTENCY_CONFLICT` | |
| No `clientOrderId` while the server requires one | **400** | `{ "error": "clientOrderId is required.", "code": "CLIENT_ORDER_ID_REQUIRED" }` | |

Branch on the **status code** (201 vs 200) or the header. The header is exposed to the browser through CORS.

A replay:
- creates nothing: no second order, stock decrement, payment row, kitchen ticket or display event;
- returns the sale **as it is now**, so `status` may have moved on (`IN_PROGRESS`, `DONE`, `VOIDED`) and `adjustments` may be filled. `id`, `orderNumber`, totals, lines and payments are the original ones;
- is answered **before** the shift, stock and manager-approval checks. A retry succeeds even after the shift has closed or the item has sold out, and never needs a new manager PIN, even if the discount's single-use token was already used.

### What counts as "the same payload"

The server compares a fingerprint of:

- **lines**, in the order sent: `dailyMealOptionId`, `quantity`, and each line's extras (`dailyComponentStockId`, `quantity`). The order of the extras doesn't matter.
- **payments**, in any order: `method`, `amount`, `amountTendered`, `cardReference`, `accountId`.
- **discount**: `type`, `value`, `reasonCode`, `note`.
- **complimentary**: `reasonCode`, `note`.

It ignores:
- every `authorization` object (manager tokens);
- how numbers are written: `115`, `115.0` and `115.00` match;
- spaces around text, and blank text, which counts as absent.

The order of **lines** does matter. Resend the stored request body unchanged.

### Rules for the till

1. Create the `clientOrderId` **once**, when the cashier presses Pay, and store it with the pending sale.
2. Resend the **same body with the same id** after a timeout, network error, 5xx or app restart, until you get 201 or 200.
3. Create a **new** id only for a different sale. If the cashier changes the basket or the payment, it's a new sale, so it needs a new id.
4. Treat **409 `IDEMPOTENCY_CONFLICT`** as a bug or a reused id, never as "retry". Show it and keep the pending sale for a supervisor.
5. Other errors keep their meaning: 409 `NO_OPEN_SHIFT` / `TILL_IN_USE` / stock, 400 validation, 401 `AUTHORIZATION_INVALID`. Nothing was created, so the same id can be reused once the problem is fixed, as long as the payload is unchanged. If the payload must change, use a new id.

---

## 2. Duplicate-safe voids and refunds: `POST /orders/{id}/adjustments`

### Request

| Field | Type | Rule |
|---|---|---|
| `clientRequestId` | UUID string | Optional today. One per void/refund action. |

### Responses

| Situation | Status | Body |
|---|---|---|
| New adjustment created | **201** | `OrderDto` |
| Same `clientRequestId`, same order and payload, same cashier (or an ADMIN) | **200** + `Idempotent-Replay: true` | `OrderDto` as it is now |
| Same `clientRequestId` on a **different order** | **409** | `IDEMPOTENCY_CONFLICT` |
| Same `clientRequestId`, different payload | **409** | `IDEMPOTENCY_CONFLICT` |
| Same `clientRequestId`, another cashier | **409** | `IDEMPOTENCY_CONFLICT` |
| Missing while required | **400** | `CLIENT_REQUEST_ID_REQUIRED` |

**Same payload** here means: the order id in the URL, `requestedAction`, `scope`, `reasonCode` and `note`. `authorizationToken` and `sessionToken` are ignored.

A replay doesn't restore stock again, doesn't record a second cash-out and doesn't need a new manager PIN. Without a key, retrying a void that already went through still gives the old 409 "already adjusted", so send the key.

---

## 3. Making the keys mandatory

Server setting `pos.require-client-order-id` (environment variable `POS_REQUIRE_CLIENT_ORDER_ID`), default **false**. When it's switched on, both endpoints refuse requests without a key, with the 400 codes above. It will be switched on once every till sends the keys.

---

## 4. Order numbers (Step 2, in progress)

`orderNumber` stays a per-day integer starting at 1, with no gaps. Concurrent sales from two tills never clash. In the unlikely case of a clash with old data, the server retries once and then answers **409 `ORDER_NUMBER_CONFLICT`**, never 500. That is safe to retry with the same `clientOrderId`.

---

## 5. `createdAt` on `POST /orders` (Step 4, in progress)

`createdAt` will be filled in the 201 response; today it can be null there. The format is unchanged: a UTC date-time **without** a zone suffix, e.g. `"2026-11-16T10:31:07.123"`. Read it as UTC.

---

## 6. `GET /health` (Step 5, in progress)

Public. No token, no device registration, no login protection. Meant for the till's online/offline detection and clock check.

```json
{ "status": "UP", "serverTimeUtc": "2026-11-16T10:31:07.123Z" }
```

| Situation | Status | `status` |
|---|---|---|
| API and database reachable | **200** | `"UP"` |
| Database unreachable or slower than 2 s | **503** | `"DOWN"` (`serverTimeUtc` still present) |

The database check is cached for 5 seconds, so polling every few seconds is cheap. To estimate clock drift, compare `serverTimeUtc` with the till's clock, allowing for half the round-trip time.
