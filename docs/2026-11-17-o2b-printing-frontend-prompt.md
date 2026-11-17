# O2b printing: frontend prompt

The prompt for the frontend's O2b work (receipt and kitchen-ticket printing). It's the reviewed version: it follows `docs/2026-11-17-o2b-receipt-data-api-contract.md` and `docs/2026-11-17-o2b-frontend-handoff.md`. Paste everything inside the block below.

```
ROLE AND RULES
You are implementing receipt and kitchen-ticket printing (O2b) in the canteen POS frontend.
Same working rules as before: plan first and wait; show diffs for existing files; branch
`feature/o2b-printing` from main or the O2 frontend branch (say which; the O2 and O2b backend
isn't merged into main yet); commit per step, do not push or merge; list every file created
or modified at the end.

BUILD AGAINST
- docs/2026-11-17-o2b-receipt-data-api-contract.md (the backend contract; the source of truth)
- docs/2026-11-17-o2b-frontend-handoff.md (what to print, the field rules, the kitchen-ticket
  and reprint rules, and the release checklist)
If the code or these docs contradict this prompt, stop and report.

APPROVED APPROACH
Browser printing with Chrome or Edge in kiosk mode with silent printing (--kiosk
--kiosk-printing) to an 80 mm thermal printer set as the Windows default. Receipt first, then
the kitchen ticket, on the same printer. The cash drawer is manual: no drawer integration.

TASKS
1. Print automatically after every successful sale: the customer receipt, then the kitchen
   ticket.
   - Reuse usePrint, and add a print queue so the two jobs run one after the other. Move on
     at afterprint, with a short timeout as a fallback, because afterprint isn't reliable
     with kiosk printing.
   - Make each automatic print switchable with per-till settings stored locally: "Print
     receipt automatically" and "Print kitchen ticket automatically". Propose where an admin
     changes them. Clearing the browser data resets them; the setup guide must say so.
   - If GET /pos/receipt-settings has failed, print without the header. Never block or fail
     a sale because of printing.

2. Receipt layout. Generalise ReceiptView so it takes an order object (O4 will build one
   locally, without orderNumber, receiptNumber or VAT values: allow those to be missing).
   - Header, from GET /pos/receipt-settings (fetched after sign-in, cached for the session):
     receiptTitle (e.g. "Tax Invoice"; skip when null), business name, address lines (0 to 4),
     phone, VAT number. Skip any empty field.
   - receiptNumber (e.g. 20261118-0037) as the receipt's number, and orderNumber LARGE as the
     collection number (customers collect by it). Never build either yourself.
   - Till code and name (skip the line when tillCode is null: the unregistered till), cashier
     name, date and time. createdAt is UTC without a zone suffix: convert it to
     Africa/Johannesburg.
   - Lines with extras, quantities and prices. Print every extra as sold, including ones
     marked adjusted, so the lines add up to the total.
   - Discount at sale (preDiscountTotal, saleDiscount; skip when 0).
   - Total: originalTotal, never total (total drops after a void or refund).
   - VAT line, only when showVatLine is true AND vatIncludedAmount isn't null (it's null on
     orders from before O2b): "Incl. VAT {vatRate × 100}%: R x.xx" using the server's
     vatIncludedAmount. Never hard-code 15% or work VAT out yourself.
     Print it on one line under the total. Show the rate without trailing zeros (0.1500 → 15,
     0.1550 → 15.5), and keep the wording in one constant: the accountant may change it.
   - Payments: method, amount, amountTendered and changeDue (cash), cardReference (card),
     and for ACCOUNT payments the account's name looked up from GET /accounts by accountId
     (there is no account number on the order).
   - Complimentary orders (orderKind COMPLIMENTARY): 0.00 totals and the complimentary reason.
   - Footer text (may hold line breaks).
   - "COPY n" on an admin reprint (reprint.copyNumber); "COPY" with no number on a local
     reprint (task 4).
   - Fit 72 mm print width; no browser headers or footers; no margins.

3. Kitchen ticket layout
   - Print it only when at least one line has station KITCHEN; skip it otherwise (for
     example cold drinks only, station NONE, handed over at payment).
   - List only the KITCHEN lines, with their extras and quantities. No prices. BAR lines
     stay on the bar's display screen and get no paper ticket.
   - Very large orderNumber, local time, till code (when not null).
   - Orders have no notes field: print none.

4. Completion screen
   - "Print receipt again" and "Print kitchen ticket again" buttons: a local reprint from the
     order on screen, with no server call. Show them only until the next sale starts. A local
     receipt reprint is marked "COPY" with no number; numbered copies come only from the
     admin reprint flow.
   - Keep "Mark Print Failed" and the admin reprint flow as they are. The browser can't detect
     print failures, so marking stays manual.

5. Till setup guide: docs/YYYY-MM-DD-till-printing-setup.md
   - Chrome or Edge kiosk shortcut with --kiosk and --kiosk-printing, on a normal (not
     private) profile, so the till's registration cookie and print settings persist.
   - Setting the thermal printer as the Windows default.
   - Paper size 80 mm, driver auto-cut per job, and a test print.
   - Disabling browser headers and footers.
   - What to do when the printer is out of paper (reload paper, then "Print receipt again"
     or "Mark Print Failed").
   - Clearing the browser data resets the auto-print switches (and the till registration).

6. Admin Receipt settings screen
   - Where: the back-office admin area, ADMIN only (hidden for other roles).
   - Load with GET /admin/receipt-settings. Save with PUT /admin/receipt-settings, sending
     every field: start from what GET returned and change only what the admin edited. A
     field left out is saved as null (contract 2.4).
   - Fields: receiptTitle, businessName, addressLines (up to 4 inputs), phone, vatNumber,
     footerText (multi-line), showVatLine (checkbox). Mirror the limits in contract 2.1 and
     handoff section 7.
   - Show "Last saved by {updatedBy} at {updatedAt}" (updatedAt is an ISO instant with Z;
     show local time). Hide the line when both are null.
   - Show each 400 message under its field; one response can hold several fields.

TESTS
- The print queue runs the receipt, then the kitchen ticket, including when afterprint never
  fires (the timeout).
- Settings switch each auto-print off.
- Receipt content from a sample OrderDto: receiptTitle and receiptNumber, the large order
  number, VAT, card reference, account name, tendered and change.
- No VAT line when showVatLine is false, or when vatIncludedAmount is null.
- originalTotal printed after a refund, with adjusted extras still listed.
- Complimentary order: 0.00 and the reason.
- Unregistered till: no till line. createdAt printed in local time.
- Empty receipt settings: the receipt still prints.
- Kitchen ticket: skipped for NONE-only orders; a mixed order's ticket lists only KITCHEN
  lines.
- Reprint buttons: local reprint marked "COPY" with no number; buttons gone once the next
  sale starts.
- VAT rate shown without trailing zeros (0.1500 → 15%).
- Settings screen: load and save; field errors shown under their fields; receiptTitle kept
  when not edited; screen hidden for non-ADMIN.
- Layout snapshot at 72 mm.

FIRST REPLY: the plan only.
```
