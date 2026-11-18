# O4 offline selling: answers to the frontend's questions

Answers to the questions the frontend raised in its O4 plan (`docs/2026-11-18-o4-offline-selling-frontend-prompt.md`). They follow `docs/2026-11-18-o4-offline-sync-api-contract.md` and `docs/2026-11-17-o2b-receipt-data-api-contract.md`. Questions 3 and 4 were decisions rather than contract facts. Paste everything inside the block below.

```
Answers

1. VAT examples (O2b contract, section 3, at 15%; vatIncluded = round(total × rate ÷ (1 + rate), 2, half up)):
   | total     | vatIncluded | working               |
   | 50.00     | 6.52        | 6.5217…               |
   | 100.00    | 13.04       | 13.0434…              |
   | 33.33     | 4.35        | 4.3473…               |
   | 1.15      | 0.15        | exactly               |
   | 0.01      | 0.00        | 0.0013…               |
   | 0.00      | 0.00        | complimentary / zero  |
   | 99999.99  | 13043.48    | 13043.4769…           |
   The backend's own tests also use 85.00 → 11.09, and at a 100% rate 0.01 → 0.01 and 0.03 → 0.02
   (half up), and a 0% rate → 0.00.

2. Unlimited counts: yes. portionsRemaining: null (always-on light meals and beverages) and
   bufferRemaining: null both mean "not counted, no limit". The server skips the decrement for
   them too.

3. INVALID and REFUSED entries (decision):
   - Count them in pendingCount and in pending[]. It's the truth: those sales were made and aren't
     in the server's books.
   - They don't block anything for good: a manager can force-close the shift (never blocked) and
     close the day with the audited OFFLINE_SALES_PENDING override.
   - On the devices page: list them with the server's result and error, and a "Copy details for
     support" button.
   - A local ADMIN action "Handed to support" (ADMIN sign-in on the till, plus a reason): the entry
     is kept but no longer counted. No new endpoint for now.
   - Both should be rare. INVALID means a till bug. REFUSED means a bad or foreign grant; key
     rotation is already covered by the previous-key setting.

4. Stuck online sales:
   - With a discount or complimentary (decision): don't convert them. They stay in O1's "Checking
     the sale…" state, parked and retried when the till is back online. The cashier may keep selling
     offline meanwhile. Offline sales can't carry either, and the online retry settles it.
   - ACCOUNT, already approved by a manager online: convert it, and count it against accountCap
     (the server counts every ACCOUNT payment under the grant too). If the online sale never landed,
     the server flags ACCOUNT_APPROVAL_PENDING, which is right: the approval wasn't recorded.
   - CARD: ask the cashier for the slip reference before printing (D8, required). Only if they
     can't (no slip) may it go without one; the server then raises CARD_REFERENCE_MISSING.

5. The large number: agreed. When an order has a collectionNumber, print it as the large number on
   receipts and kitchen tickets, instead of orderNumber. That covers offline sales, their reprints,
   and online sales that took a slip's numbers through DUPLICATE.

6. Sign out while offline: disable it, with the reason on the button ("Offline: lock the till
   instead"). Lock stays available, because only the grant's cashier can unlock offline.

7. Till filter: yes, use GET /admin/tills (deviceId, tillCode, tillName). It lists active tills
   only, so also offer "All tills". Each exception item carries its own tillCode and deviceId,
   which covers exceptions from tills revoked since.

Approved to proceed with step 1, with these answers.
```
