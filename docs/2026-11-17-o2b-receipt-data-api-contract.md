# Receipt data: API contract (O2b)

What the backend gives the frontend to print a receipt: the business details (receipt settings), the VAT included in each sale, and who rang it up on which till. It builds on the O2 contract (`docs/2026-11-17-o2-tills-shifts-api-contract.md`) and the O1 contract (`docs/2026-11-16-o1-resilience-api-contract.md`), which still apply.

**Status:** implemented on `feature/o2b-receipt-data` (see `docs/2026-11-17-o2b-receipt-data-change-report.md`). Section 8's open questions aren't built yet.

## Contents

1. What a receipt is built from
2. Receipt settings
3. VAT on sales: the rule
4. New fields on orders
5. Example order (201 from `POST /orders`)
6. Reprints and orders from before O2b
7. Printing notes for the frontend
8. Decisions and open questions

## 1. What a receipt is built from

| Part of the receipt | Comes from |
|---|---|
| Business name, address, phone, VAT number, footer | `GET /pos/receipt-settings` (section 2), read once after sign-in and cached for the session |
| Order number, date, time | `orderNumber`, `orderDate`, `createdAt` (UTC, section 7) |
| Till and cashier | `tillCode`, `tillName`, `cashierName` (section 4) |
| Lines and extras | `lines[]` (name, quantity, unit price, line total, extras) |
| Discount at sale | `preDiscountTotal`, `saleDiscount`, `discount` |
| Total | `originalTotal` (the sale as rung; see section 6 for `total`) |
| Payments, tendered, change | `payments[]` (`method`, `amount`, `amountTendered`, `changeDue`, `cardReference`) |
| VAT line | `vatRate`, `vatIncludedAmount` (section 3), printed only when `showVatLine` is true |
| Complimentary | `orderKind: "COMPLIMENTARY"`, `complimentary` |
| Reprint marking | `reprint.copyNumber` on the reprint response |

Everything is in the **201 response of `POST /orders`**, the **200 O1 replay** of the same sale, `GET /orders/{id}`, and the **reprint response** (`POST /admin/orders/{id}/reprint-receipt`). No extra call is needed per order.

## 2. Receipt settings

One set of settings for the whole site for now (per tenant later, WS2-5). Every value may be empty; nothing is filled in by the installation.

### 2.1 Shape

```json
{
  "businessName": "Canteen at Riverside Park",
  "addressLines": ["12 Main Road", "Riverside", "Cape Town 8001"],
  "phone": "021 555 0100",
  "vatNumber": "4123456789",
  "footerText": "Thank you! Keep this slip for refunds.",
  "showVatLine": true,
  "updatedAt": "2026-11-18T07:30:00Z",
  "updatedBy": "Canteen Admin"
}
```

| Field | Rules |
|---|---|
| `businessName` | at most 100 characters, or `null` |
| `addressLines` | 0 to 4 lines, each at most 100 characters; empty lines are dropped |
| `phone` | at most 50 characters, or `null` |
| `vatNumber` | at most 20 characters, or `null` |
| `footerText` | at most 500 characters (may contain line breaks), or `null` |
| `showVatLine` | `true` or `false`; **default `false`**, so no VAT line prints until an admin sets this up |
| `updatedAt`, `updatedBy` | when and by whom it was last saved; `null` before the first save. Admin endpoints only. |

Text is trimmed; an empty or blank value is stored as `null`.

### 2.2 `GET /pos/receipt-settings`

For printing. CASHIER or ADMIN (the `/pos/**` rule); 401 without sign-in, 403 for other roles.

**200**, the shape above without `updatedAt` and `updatedBy`. Before anything is saved:

```json
{"businessName": null, "addressLines": [], "phone": null, "vatNumber": null, "footerText": null, "showVatLine": false}
```

### 2.3 `GET /admin/receipt-settings`

ADMIN only. **200**, the full shape (2.1).

### 2.4 `PUT /admin/receipt-settings`

ADMIN only. No password re-sign-in needed (unlike till management). Replaces all the settings; send every field.

**Request:**

```json
{
  "businessName": "Canteen at Riverside Park",
  "addressLines": ["12 Main Road", "Riverside", "Cape Town 8001"],
  "phone": "021 555 0100",
  "vatNumber": "4123456789",
  "footerText": "Thank you! Keep this slip for refunds.",
  "showVatLine": true
}
```

**Responses:**

| Situation | Response |
|---|---|
| Saved | 200, the full shape (2.1) |
| A value too long, or more than 4 address lines | 400 field map, e.g. `{"addressLines": "At most 4 address lines."}`, `{"vatNumber": "VAT number must be 20 characters or fewer."}` |
| `showVatLine` missing | 400 `{"showVatLine": "showVatLine is required."}` |
| Not an ADMIN | 403 |

Settings changes apply to the next receipt printed. Receipts already printed aren't affected; reprints use the settings current at the time of the reprint.

## 3. VAT on sales: the rule

Every new sale and complimentary order stores the VAT rate and the VAT included in its total, once, when it's created:

```
vatRate           = the standard VAT rate in force (setting vat.standard-rate, 0.15)
vatIncludedAmount = round( originalTotal × vatRate ÷ (1 + vatRate), 2 decimals, half up )
```

- **Prices include VAT.** `vatIncludedAmount` is the part of the total that is VAT; it is never added on top.
- **The total it's taken on** is `originalTotal`: the sale as rung, **after** a discount given at sale and **before** any later void or refund. A complimentary order has `originalTotal` 0.00, so `vatIncludedAmount` is 0.00 (the rate is still stored).
- **One rounding.** Multiply exactly, divide once, then round to 2 decimals with half up. No rounding in between, no per-line VAT.
- **Never recomputed.** Voids and refunds don't change it, and neither does a later change of the rate.
- **All items are treated as standard-rated** (the menu has no VAT code per item).
- **The offline till (O4) must apply exactly this rule** to sales it records offline.

**Examples at 15%:**

| `originalTotal` | `vatIncludedAmount` | Working |
|---|---|---|
| 50.00 | 6.52 | 50 × 0.15 ÷ 1.15 = 6.5217… |
| 100.00 | 13.04 | 13.0434… |
| 33.33 | 4.35 | 4.3473… |
| 1.15 | 0.15 | 0.15 exactly |
| 0.01 | 0.00 | 0.0013… |
| 0.00 | 0.00 | complimentary, or fully discounted |
| 99 999.99 | 13 043.48 | 13 043.4769… |

At 15% an exact half cent can't occur, so "half up" only matters for other rates (for example at 100%, 0.01 gives 0.005, which rounds to 0.01).

## 4. New fields on orders

On `OrderDto` (every order response, including reprints):

| Field | Type | Meaning |
|---|---|---|
| `vatRate` | number (4 decimals) | The rate used, e.g. `0.1500`. `null` on orders from before O2b. |
| `vatIncludedAmount` | number (2 decimals) | The VAT included in `originalTotal` (section 3). `null` on orders from before O2b. |
| `cashierName` | text | The person who rang the order up (`cashierId`'s name). |
| `tillCode` | text | The till it was rung up on (since O2). `null` on the unregistered till and orders from before O2. |
| `tillName` | text | That till's name as it was when the shift opened. `null` when `tillCode` is `null`. |

Existing fields are unchanged.

## 5. Example order (201 from `POST /orders`)

A cash sale of two items on T1, no discount:

```json
{
  "id": 9041,
  "orderNumber": 37,
  "orderDate": "2026-11-18",
  "shiftId": 412,
  "tillCode": "T1",
  "tillName": "Till 1 (front)",
  "cashierId": 17,
  "cashierName": "Thandi Mokoena",
  "status": "PENDING",
  "orderKind": "SALE",
  "subtotal": 85.00,
  "total": 85.00,
  "originalTotal": 85.00,
  "preDiscountTotal": 85.00,
  "saleDiscount": 0.00,
  "vatRate": 0.1500,
  "vatIncludedAmount": 11.09,
  "discount": null,
  "complimentary": null,
  "createdAt": "2026-11-18T10:31:07.123",
  "printFailed": false,
  "lines": [
    {
      "id": 20311, "station": "KITCHEN", "dailyMealOptionId": 801, "name": "Beef stew",
      "unitPrice": 50.00, "quantity": 1, "lineTotal": 65.00,
      "extras": [
        {"id": 5521, "dailyComponentStockId": 3102, "componentName": "Extra beef", "priceDelta": 15.00, "quantity": 1, "lineTotal": 15.00, "adjusted": false}
      ]
    },
    {
      "id": 20312, "station": "NONE", "dailyMealOptionId": 912, "name": "Still water 500 ml",
      "unitPrice": 20.00, "quantity": 1, "lineTotal": 20.00, "extras": []
    }
  ],
  "stations": [{"station": "KITCHEN", "status": "PENDING", "updatedAt": "2026-11-18T10:31:07.123Z", "updatedBy": null}],
  "adjustments": [],
  "payments": [
    {"id": 7710, "method": "CASH", "amount": 85.00, "amountTendered": 100.00, "changeDue": 15.00}
  ]
}
```

85.00 × 0.15 ÷ 1.15 = 11.0869…, so `vatIncludedAmount` is 11.09.

A **reprint** (`POST /admin/orders/{id}/reprint-receipt`) returns the same fields plus:

```json
"reprint": {"copyNumber": 2, "reprintedBy": {"id": 3, "name": "Canteen Admin"}, "reprintedAt": "2026-11-18T12:05:41Z"}
```

## 6. Reprints and orders from before O2b

- **Orders from before O2b** have `vatRate` and `vatIncludedAmount` `null`. **Print no VAT line** for them, even when `showVatLine` is true. Their original receipts had none, and the backend doesn't guess a VAT amount for history.
- **After a void or refund**, `total` is lower than `originalTotal`. A reprint is a copy of the sale: print `originalTotal` and the VAT stored with it, and list the voids and refunds from `adjustments` separately if you show them. Don't recompute VAT from `total`.
- **Cashier and till** on a reprint are the ones who rang the sale up, not the person reprinting (that's `reprint.reprintedBy`).
- **Business details** on a reprint come from the current receipt settings.

## 7. Printing notes for the frontend

- **`createdAt` is UTC without a zone suffix** (O1 contract, section 5). Convert it to local time (Africa/Johannesburg) before printing.
- **VAT line** (when `showVatLine` is true and `vatIncludedAmount` isn't `null`), for example: `Total incl. VAT 85.00` and `VAT (15%) included 11.09`. Show the rate as `vatRate × 100` without trailing zeros.
- **Complimentary orders:** `originalTotal` and `vatIncludedAmount` are 0.00; print the complimentary reason, and the VAT line only if you print one for every receipt.
- **Empty settings:** skip any empty field; a receipt with no business name is valid.
- **Line names** are the menu option's current name (they aren't stored with the order), so renaming a menu item changes the name on later reprints. See question 3.

## 8. Decisions and open questions

**Taken as proposed in the plan (approved):**
- `showVatLine` defaults to false.
- All items are standard-rated; VAT is worked out on the order total, not per line.
- Saving the settings needs ADMIN only, without a recent password sign-in.
- Orders from before O2b aren't backfilled.

**Open (not built until answered):**
1. **Business name in reports.** The X, Z and Cashier readings print the server setting `app.business.name`. Proposed: read the receipt settings' business name instead, and fall back to the setting when it's empty.
2. **Tax invoice.** If the business is VAT-registered, an abridged tax invoice normally needs the words "Tax Invoice", the VAT number and a unique serial number. `orderNumber` restarts each day, so on its own it isn't unique. Proposed: print `orderDate` + `orderNumber` (for example `20261118-0037`) or the order `id`, and make the receipt title a setting. To confirm with the accountant.
3. **Line names on reprints.** Storing each line's name with the order (a column on order lines, filled at sale) would make reprints exact. Not in O2b unless approved.
