# O2b receipt data: frontend handoff

**For:** the POS frontend.
**Backend:** `feature/o2b-receipt-data` (not merged yet). 825 of 825 tests pass.
**Contract:** `docs/2026-11-17-o2b-receipt-data-api-contract.md` is the source of truth. This note says what to build and flags the two easy mistakes. Section numbers below refer to the contract.

## 1. What to build

| # | Screen or flow | What to do | Contract |
|---|---|---|---|
| 1 | After POS sign-in | Call `GET /pos/receipt-settings` once and cache it for the session. Fetch it again after the next sign-in. | 2.2 |
| 2 | Printing a receipt (sale, complimentary, reprint) | Build the slip from the order response you already have (201, O1 replay, `GET /orders/{id}` or the reprint response) plus the cached settings. No extra call per order. | 1, 5 |
| 3 | Admin: Receipt settings screen | Read `GET /admin/receipt-settings` and save with `PUT /admin/receipt-settings` (ADMIN only). Show `updatedBy` and `updatedAt` as "Last saved by … at …". | 2.3, 2.4 |
| 4 | Reports (X, Z, Cashier reading) | Nothing to do: `meta.business.name` now comes from the receipt settings, with a fallback. | 8.2 |

## 2. Two things to get right

1. **The settings PUT replaces everything.** A field you leave out is saved as `null`. Start from the full `GET /admin/receipt-settings` response, change only what the user edited, and send it all back, including `receiptTitle` and `showVatLine`. `showVatLine` is required; leaving it out gives a 400.
2. **Two different numbers.** Print `receiptNumber` (e.g. `20261118-0037`) as the receipt's number, next to the title. Keep `orderNumber` (e.g. `37`) as the short collection number called out at the counter. Don't build either one yourself; both come in every order response.

## 3. Slip layout (suggested)

Print each part only when it has a value. Empty settings are normal: a slip with no business name is valid.

```
            Tax Invoice                     <- receiptTitle (skip when null)
     Canteen at Riverside Park              <- businessName
          12 Main Road                      <- addressLines[] (0 to 4)
           Riverside
         Tel 021 555 0100                   <- phone
        VAT No. 4123456789                  <- vatNumber
Receipt 20261118-0037       Order 37        <- receiptNumber, orderNumber
18/11/2026 12:31  Till T1 (Till 1 front)    <- createdAt in local time; tillCode, tillName
Cashier: Thandi Mokoena                     <- cashierName
----------------------------------------
1 x Beef stew                      50.00    <- lines[].name, quantity, lineTotal
    + Extra beef                   15.00    <- extras[].componentName (skip adjusted)
1 x Still water 500 ml             20.00
----------------------------------------
Discount                          -10.00    <- saleDiscount (skip when 0)
TOTAL                              75.00    <- originalTotal
Cash                              100.00    <- payments[]: amountTendered
Change                             25.00    <- changeDue
Total incl. VAT 75.00                       <- the VAT line, only when showVatLine
VAT (15%) included                  9.78       is true and vatIncludedAmount isn't null
----------------------------------------
  Thank you! Keep this slip for refunds.    <- footerText (may hold line breaks)
             ** COPY 2 **                   <- reprint.copyNumber, reprints only
```

## 4. Field rules

| Field | Rule | Contract |
|---|---|---|
| `createdAt` | UTC with no zone suffix. Convert to Africa/Johannesburg before printing. | 7 |
| `vatRate`, `vatIncludedAmount` | `null` on orders from before O2b: print no VAT line for those, even when `showVatLine` is true. Never work VAT out yourself. Print the rate as `vatRate × 100` without trailing zeros (`15%`). | 3, 6 |
| `tillCode`, `tillName` | `null` on the unregistered till. Print no till line then. | 4 |
| `lines[].name`, `extras[].componentName` | The names at sale. Print them as they come. | 4 |
| `originalTotal` | The amount to print as the total, also on reprints. `total` drops after a void or refund, so don't print it as the receipt's total. | 6 |
| Complimentary orders | `orderKind: "COMPLIMENTARY"`: totals and VAT are 0.00. Print the complimentary reason from `complimentary`. | 7 |
| Reprints | Cashier and till are the original sale's. The person reprinting is in `reprint.reprintedBy`. Business details come from the current settings. | 6 |

## 5. Admin settings screen: validation to mirror

The backend checks the same rules, and returns a 400 map of field name to message (contract 2.4). Show each message under its field.

| Field | Limit |
|---|---|
| `receiptTitle` | 50 characters |
| `businessName` | 100 characters |
| `addressLines` | at most 4 lines, each 100 characters (blank lines are dropped) |
| `phone` | 50 characters |
| `vatNumber` | 20 characters |
| `footerText` | 500 characters, line breaks allowed |
| `showVatLine` | a checkbox; always send it |

Values are trimmed, and blank means "none".

## 6. Still to confirm (not frontend work)

The accountant still has to confirm the receipt title ("Tax Invoice" or other) and the `receiptNumber` format. Neither needs a frontend change if they're adjusted: the title is a setting, and the number arrives ready to print.

## 7. Checks before release

- [ ] A sale prints with every settings field empty (fresh install).
- [ ] A sale prints with every field filled, including a 4-line address and a multi-line footer.
- [ ] The VAT line appears only when `showVatLine` is true, and never for an order from before O2b.
- [ ] A complimentary order prints 0.00 and its reason.
- [ ] A reprint after a refund shows `originalTotal`, the original cashier and till, and the copy number.
- [ ] The receipt time is local time, not UTC.
- [ ] Saving the admin screen without touching `receiptTitle` keeps it.
- [ ] A 400 from the settings PUT shows the message under the right field.
