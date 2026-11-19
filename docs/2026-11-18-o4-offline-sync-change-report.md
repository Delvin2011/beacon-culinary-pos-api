# O4 offline sync: change report

**Branch:** `feature/o4-offline-sync`, from `feature/o3-offline-foundation` (O3 isn't merged into `main` or pushed yet). Not pushed or merged.
**Contract:** `docs/2026-11-18-o4-offline-sync-api-contract.md` (the frontend builds against it).
**Tests:** 921 of 921 pass on the full suite. O3 ended at 884; O4 adds 37.
**Database:** one migration, `V111__o4_offline_sync.sql`, applied to the test database (`BeaconculinaryTest`) only.
**Switch:** sync works whenever a till holds a grant; grants exist only while `pos.offline.enabled` is on (O3).

## 1. What changed

Tills can now hand over the sales they made offline, and managers can review what needs attention.
- **Sync:** `POST /pos/offline/sales` takes up to 50 sales, authenticated by the till's cookie (a revoked or expired one too) and the signed grants they were made under. Each sale is recorded on its own; one bad sale never stops the rest; resending is safe.
- **Recording:** a completed sale is never refused. It is recorded as charged and already collected (no kitchen, bar or board), on the grant's shift and cashier, with the till's receipt and collection numbers. Anything the server would have done differently becomes a **sale exception**: 23 codes, REVIEW or INFO.
- **Review:** managers list, filter and resolve exceptions, and approve offline account charges (recording themselves as the approver). Open counts show in the Tills view and on the dashboard.
- **Reports:** offline sales count on their day (or the day they were booked to after a Z), at the hour they were sold. The X and Z have an Offline sales section; cash taken offline but recorded after its shift closed shows as **late offline cash** on its own line. Z definition 3.

## 2. Commits

| Step | Commit | What |
|---|---|---|
| 1 | `49b57db` | API contract (and the O3 report's test count after the CORS fix) |
| 2 | `1e626c6` | V111; offline order fields and receipt numbers; lines without a menu link; sale exception store |
| 3 | `2c87487` | `POST /pos/offline/sales`, the recording path, every exception check; previous grant key |
| 4 | `8e2b4a2` | Exception review endpoints; counts in admin tills and the dashboard |
| 5 | `5e2766b` | Offline section in X and Z, late offline cash, reconciliation, Z definition 3 |
| 6 | (this commit) | This report |
| Follow-up | `11a3a98` | A `DUPLICATE` of a stuck online sale (O1 outcome unknown, then printed offline) takes the offline slip's receipt and collection numbers, so reprints match the slip; contract status line |

## 3. The tasks

| Task | Status | Where |
|---|---|---|
| 1. Batch sync, at most 50 | Done (also 10 grants, 512 KB). Per-sale results CREATED, CREATED_WITH_EXCEPTIONS, DUPLICATE, INVALID, REFUSED | `OfflineSyncService`, `OfflineSyncController` |
| Auth (G) | Till cookie, VALID, REVOKED or EXPIRED; unknown or missing: 403. Each grant's signature and till checked; a bad one makes its sales REFUSED | `OfflineSyncService` |
| Idempotency, duplicates of online sales (H) | On `clientOrderId` (O1's unique index); a concurrent duplicate is read back. `DUPLICATE_DIFFERS` once, on the existing order. A stuck online sale printed offline takes the slip's numbers (follow-up) | `OfflineSaleRecorder` |
| 2. Recording path | A new service, not `OrderService.createOrder`. No clock checks; `PERIOD_CLOSED` and `ITEM_UNAVAILABLE` as INFO | `OfflineSaleRecorder` |
| Trading day, Z (D6) | `soldAt`'s Johannesburg date; after a Z, booked to the first later day without one (`LATE_AFTER_Z`); `orderNumber` from O1's per-day allocator | `OfflineSaleRecorder` |
| New order columns | `recorded_offline`, `offline_receipt_number`, `collection_number`, `sold_at`, `offline_grant_id`, `offline_trading_day`, `offline_seq`, `booked_trading_day`; unique (till, day, seq) | V111 |
| Number clash | Recorded without its sequence number, receipt number as printed, `OFFLINE_NUMBER_CLASH` | `OfflineSaleRecorder` |
| Highest number seen | Raised per till and day (O3 `recordSeen`) | `OfflineSaleRecorder` |
| Shift | The grant's, even if closed (`LATE_AFTER_SHIFT_CLOSE`) | `OfflineSaleRecorder` |
| Prices (D5), unknown items | Till's price stands, `PRICE_MISMATCH`; unknown ids recorded by name and price without a menu link, `UNKNOWN_ITEM` | V111, `OfflineSaleRecorder` |
| Portions (D4) | Decremented, never below 0 (the CHECK constraints stay); shortfall in `OVERSOLD` | `OfflineSaleRecorder` |
| Light-meal stock | Consumed, `occurred_at` = `soldAt`; `STOCK_AFTER_COUNT` (INFO) when a later stock take counted that ingredient | `LightMealStockService`, `IngredientStockMovement` |
| VAT | Recomputed (O2b rule) and stored; `VAT_MISMATCH` on a printed difference | `OfflineSaleRecorder` |
| Payments, accounts (A, D8) | As taken; `PAYMENT_MISMATCH`, `CARD_REFERENCE_MISSING`, `ACCOUNT_APPROVAL_PENDING` (every one), `ACCOUNT_OVER_CAP` (per account per grant), `ACCOUNT_INACTIVE` | `OfflineSaleRecorder` |
| Collected, no screens (D) | Status COLLECTED, no station rows, one audit row, nothing published | `OfflineSaleRecorder` |
| Time and grant checks (D9) | `CLOCK_SKEW` (5 min), `FUTURE_SALE` (1 min), `OUTSIDE_GRANT`, `DEVICE_REVOKED`, `GRANT_MISMATCH`, `UNLOCKED_OFFLINE` | `OfflineSaleRecorder` |
| 3. OrderDto and reprints (C) | `recordedOffline`, `soldAt`, `collectionNumber`, `bookedTradingDay`; `receiptNumber` = the till's for offline sales, reprints included | `OrderDto`, `OrderMapper` |
| Reports use `sold_at` | Sale hour from `sold_at`; trading day from `booked_trading_day` | V111 report views |
| 4. Exceptions | Table, list with filters and paging, detail, resolve, approve-account; counts in Tills and the dashboard | `offline/exceptions/*`, `AdminTillsService`, `AttentionSection` |
| Close Day | Warns (X check `offline-exceptions`), doesn't block | `EndOfDayStatements` |
| 5. Status | The till's heartbeat after each batch (O3); `OFFLINE_SALES_PENDING` clears at 0 | unchanged |
| 6. Reports | Offline section, late offline cash line (X, Z, cashier reading), reconciliation excludes late cash, Z definition 3 | `EndOfDayStatements`, `ShiftCashFigures`, `OfflineSalesFacts`, `ZReadingReport` |
| 7. Contract | Committed first; corrected in step 3 (`lineTotal`, 500) and step 5 (reconciliation note) | the contract |

## 4. Tests (37 new)

| Class | Tests | What |
|---|---|---|
| `O4OrderFieldsIntegrationTests` | 5 | Online sales have no offline fields; offline numbers on GET and reprint; a line without a menu link shows its name and can be refunded; booked day and sale hour in the report views; the exception store |
| `O4OfflineSyncIntegrationTests` | 20 | Created and collected (no station rows, one audit row, portions, sequence); duplicates and `DUPLICATE_DIFFERS`; duplicate of an online sale; invalid sales in a batch; refusals (bad signature, another till's grant, no cookie, bad batch); after the meal period and midnight; after shift close; after a Z (booked two days on); oversold; price mismatch; unknown item; VAT; payments and totals; accounts (pending, cap, inactive, unknown); revoked till; replaced grant (before and after); skew, future, grant mismatch, offline unlock; number clash; light-meal stock dated `soldAt`; two tills syncing at once |
| `O4SaleExceptionsIntegrationTests` | 6 | List defaults, filters and paging; detail with the order; resolve rules; approve-account records the approver; ADMIN only; counts in Tills and the dashboard |
| `O4OfflineReportsIntegrationTests` | 4 | X offline section and warning; late cash on its own line with both reconciliation checks agreeing; a booked sale counts on the next day; Z definition 3 |
| `OfflineGrantSignerTest` (added to) | 2 | Previous key verifies after a rotation; not under another id |

One existing test changed: `O2TillReportsIntegrationTests` now expects Z definition 3.

## 5. Files

**Created (18)**
- `docs/2026-11-18-o4-offline-sync-api-contract.md`
- `docs/2026-11-18-o4-offline-sync-change-report.md`
- `src/main/resources/db/migration/V111__o4_offline_sync.sql`
- `src/main/java/com/beaconculinary/api/offline/exceptions/`: `SaleExceptionCode.java`, `SaleExceptionStore.java`, `SaleExceptionDtos.java`, `SaleExceptionService.java`, `SaleExceptionController.java`
- `src/main/java/com/beaconculinary/api/offline/sync/`: `OfflineSaleDtos.java`, `OfflineSaleValidation.java`, `OfflineSaleRecorder.java`, `OfflineSyncService.java`, `OfflineSyncController.java`
- `src/main/java/com/beaconculinary/api/reports/OfflineSalesFacts.java`
- `src/test/java/com/beaconculinary/api/offline/`: `O4OrderFieldsIntegrationTests.java`, `O4OfflineSyncIntegrationTests.java`, `O4SaleExceptionsIntegrationTests.java`, `O4OfflineReportsIntegrationTests.java`

**Modified (20)**
- `docs/2026-11-18-o3-offline-foundation-change-report.md` (test count after the CORS fix)
- `src/main/resources/application.yaml`
- `src/main/java/com/beaconculinary/api/orders/`: `Order.java`, `OrderDto.java`, `OrderMapper.java`, `OrderAdjustmentService.java`
- `src/main/java/com/beaconculinary/api/inventory/`: `LightMealStockService.java`, `IngredientStockMovement.java`
- `src/main/java/com/beaconculinary/api/offline/`: `OfflineProperties.java`, `OfflineGrantSigner.java`, `OfflineStatusDtos.java`, `OfflineStatusService.java`
- `src/main/java/com/beaconculinary/api/tills/`: `PosSecurityRules.java`, `AdminTillsService.java`
- `src/main/java/com/beaconculinary/api/dashboard/sections/AttentionSection.java`
- `src/main/java/com/beaconculinary/api/reports/`: `EndOfDayStatements.java`, `ShiftCashFigures.java`, `definitions/ZReadingReport.java`
- `src/test/java/com/beaconculinary/api/offline/OfflineGrantSignerTest.java`
- `src/test/java/com/beaconculinary/api/reports/O2TillReportsIntegrationTests.java`

**Deleted:** none.

## 6. Risks and notes

- **Late cash is defined by the flag.** A sale is late cash when the recorder raised `LATE_AFTER_SHIFT_CLOSE` (the shift was closed when the sale was recorded). The flag is never deleted, so reports don't depend on comparing the database's clock with the app's.
- **Lines without a menu link** (`UNKNOWN_ITEM`) appear in item reports under their own name with no catalogue id; voids of such lines restock nothing. Offline orders are COLLECTED, so they can be refunded but not voided.
- **A batch that hits an unexpected server error** returns 500; sales before it are already recorded and come back as DUPLICATE on the resend.
- **Body size** is checked after the body is read (the 512 KB limit refuses it, but the server has already parsed it). A proxy limit in front of the API would stop larger bodies earlier.
- **Key rotation** now works: set the old key as `POS_OFFLINE_GRANTPREVIOUSSIGNINGKEY` with its id, and grants signed with it still sync.
- **Frontend:** the review screen is expected at `/sale-exceptions` (the dashboard links there); offline sales carry no discount or complimentary.
