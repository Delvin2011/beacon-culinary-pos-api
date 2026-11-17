# O1 resilience: change report

Branch `feature/o1-resilience`, from `main` at `414efcc` (merge of PR #12, WS1-1b). Seven commits, not pushed or merged. New migration: **V106** only.

Frontend contract: `docs/2026-11-16-o1-resilience-api-contract.md`.

## Result

| Run | Result |
|---|---|
| Baseline on `414efcc`, before any change | **619 of 619** passed |
| Full suite after all steps (2026-11-16, test database) | **668 of 668** passed: 0 failures, 0 errors, 0 skipped. 102 test classes, about 4 min. 619 existing plus 49 new. |

The suite ran with Maven capped at 384 MB and the test process at 1 GB, as in earlier reports. One partial run had a machine stall: Hikari logged "thread starvation or clock leap" of 3½ minutes. The code wasn't involved, and the full run was unaffected.

## Commits

| Commit | Step |
|---|---|
| `f274c9e` | 1: idempotent sales and adjustments |
| `487b94b` | API contract |
| `51f768e` | 2: order-number race |
| `47b5c7f` | 3: display events after commit |
| `c14f294` | 4: `createdAt` in the POST response |
| `a710638` | 5: `GET /health` |
| `3b6b7db` | 6: approval-token time zone report (report only) |

## 1. Idempotent sales and adjustments

- `POST /orders` takes an optional `clientOrderId`, and `POST /orders/{id}/adjustments` an optional `clientRequestId` (UUID).
  - Same key and same payload: **200** with the order and `Idempotent-Replay: true`.
  - Different payload, a different order, or another cashier (not an ADMIN): **409 `IDEMPOTENCY_CONFLICT`**.
- **V106** adds `client_order_id` / `client_request_id` (UNIQUEIDENTIFIER) and `client_request_hash` (SHA-256) to `orders` and `order_adjustments`, with filtered unique indexes.
- **"Same payload"** is defined in `RequestFingerprint`:
  - Sales: lines in order with extras sorted, payments sorted by method (amount, tendered, card reference, account), discount, complimentary.
  - Adjustments: order, action, scope, reason, note.
  - Manager tokens are left out, numbers ignore scale (`12.5` = `12.50`), and text is trimmed.
- **Duplicates are serialised** by an SQL Server application lock on the key, taken before the shift, stock and token checks. A retry therefore succeeds after the shift closes and never needs a new manager PIN. For adjustments this matters because `consumeToken` commits on its own.
- **Backstop:** if the unique index still refuses a duplicate, `OrderSubmissionService` re-reads the record after the rollback and returns it.
- **Setting:** `pos.require-client-order-id` (env `POS_REQUIRE_CLIENT_ORDER_ID`), default false. When it's on, a missing key gives 400 `CLIENT_ORDER_ID_REQUIRED` / `CLIENT_REQUEST_ID_REQUIRED`.
- CORS exposes `Idempotent-Replay`. `AppLock` was extracted from `TradingDayLock`, with the same behaviour.

## 2. Order-number race

- **Change from the approved plan, agreed during the work:** no counter table. The day's number is `MAX(order_number) + 1`, read under an application lock on the day that is held until commit.
- **Why:** a stored counter would drift from orders written outside the API. `ReportFixture` and every test teardown do that, and a manual fix could too.
- **Error handling:** a lock not granted in 15 s, or a clash that survives one retry, gives **409 `ORDER_NUMBER_CONFLICT`**, never 500.
- **Finding:** sales of the *same* meal were already safe, because they queue on that meal's portion row. The race was between sales of different meals, or of always-on items (beverages, light meals), which share no stock row. The concurrency test uses a different meal per sale.
  - With the lock removed, 2 of its 3 tests failed and 24 clashes were logged.
  - With the lock, all pass and nothing clashes.

## 3. Events after commit

- The kitchen, bar and public-board listeners now use `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)`.
- `StationViewChangedEvent` now carries the kitchen and bar views, built inside the transaction, instead of the `Order` entity. That avoids lazy loading after commit.
- With the old `@EventListener`, the new tests showed a rolled-back sale and a rolled-back kitchen change both being broadcast. Now neither is.

## 4. `createdAt`

The analysis was right: the new test failed on the old code with `createdAt` null in the 201. `Order.createdAt` is now `@Generated(INSERT)`, as `Account` already is, at the cost of one extra SELECT per sale. The 201 and a replay both return the stored value. The format is unchanged: UTC with no zone suffix.

## 5. `GET /health`

- Returns `{status, serverTimeUtc}`: 200 `UP`, or 503 `DOWN` when `SELECT 1` doesn't answer within **2 s**.
- The answer is cached for **5 s**. A probe that's still hanging is waited on, not doubled. Responses carry `Cache-Control: no-store`.
- It's public:
  - not device-gated: `DeviceResolutionFilter` skips it, so there's no token lookup, last-seen write, cookie renewal or alert
  - not request-logged
  - not touched by login protection
- The tests run with device trust **required** and login protection on.

## 6. Approval-token time zone (report only)

See `docs/2026-11-16-o1-approval-token-timezone-report.md`.
- Approvals work today, but `expires_at` is stored in Johannesburg time in an otherwise UTC table.
- The API returns `expiresAt` with no zone.
- Expired rows are never deleted.
- The report proposes a fix with a migration. Nothing was changed.

## Tests added (49)

| Class | Tests | Covers |
|---|---|---|
| `orders/OrderIdempotencyIntegrationTests` | 10 | duplicate gives 200 with stock, extras, payments and events once; normalised numbers; mismatch 409; replay after shift close; discount retry with a used token; other cashier 409 / admin 200; no key; invalid key 400; concurrent duplicates (5 runs); `createdAt` |
| `orders/AdjustmentIdempotencyIntegrationTests` | 6 | retried void and refund applied once; mismatch 409; other order 409; other cashier / admin; concurrent duplicates with one single-use token |
| `orders/ClientIdRequiredIntegrationTests` | 2 | the required setting for sales and adjustments |
| `orders/RequestFingerprintTest` | 8 | fingerprint rules; backstop index detection |
| `orders/OrderNumberConcurrencyIntegrationTests` | 3 | 10 concurrent sales get 1..10 with no clash; concurrent sales with retries; numbering continues after a hand-set number |
| `orders/OrderNumberingFailureTest` | 4 | one retry, then 409; other integrity errors not retried; lock timeout gives 409 |
| `orders/OrderEventsAfterCommitIntegrationTests` | 5 | rolled-back sale and kitchen change not broadcast; committed ones broadcast once; refused sale not broadcast |
| `common/DatabaseHealthCheckTest` | 5 | up, down, timeout, cache, no stacking on a hanging query |
| `common/HealthIntegrationTests` | 4 | public with device trust required; invalid token or cookie ignored; not logged; no sign-in attempts recorded |
| `devices/DeviceResolutionFilterHealthTest` | 2 | the device filter skips only `/health` |

## Notes for deployment

- **V106** only adds nullable columns and filtered indexes, so it's quick and safe on existing data.
- `POS_REQUIRE_CLIENT_ORDER_ID` stays **false** until every till sends keys.
- Sales now take two short application locks: the key, when one is sent, and the day's number. Both are held for the rest of the sale's transaction, which is milliseconds.
- Display events now arrive just after commit instead of just before. The order is the same; the timing changes by milliseconds.

## Files

### Created (25)

- `docs/2026-11-16-o1-resilience-api-contract.md`
- `docs/2026-11-16-o1-approval-token-timezone-report.md`
- `docs/2026-11-16-o1-resilience-change-report.md` (this report)
- `src/main/resources/db/migration/V106__o1_idempotency.sql`
- `src/main/java/com/beaconculinary/api/common/AppLock.java`
- `src/main/java/com/beaconculinary/api/common/DatabaseHealthCheck.java`
- `src/main/java/com/beaconculinary/api/common/HealthController.java`
- `src/main/java/com/beaconculinary/api/common/HealthSecurityRules.java`
- `src/main/java/com/beaconculinary/api/orders/IdempotencyConflictException.java`
- `src/main/java/com/beaconculinary/api/orders/OrderNumberAllocator.java`
- `src/main/java/com/beaconculinary/api/orders/OrderNumberConflictException.java`
- `src/main/java/com/beaconculinary/api/orders/OrderResult.java`
- `src/main/java/com/beaconculinary/api/orders/OrderSubmissionService.java`
- `src/main/java/com/beaconculinary/api/orders/PosSettings.java`
- `src/main/java/com/beaconculinary/api/orders/RequestFingerprint.java`
- `src/test/java/com/beaconculinary/api/common/DatabaseHealthCheckTest.java`
- `src/test/java/com/beaconculinary/api/common/HealthIntegrationTests.java`
- `src/test/java/com/beaconculinary/api/devices/DeviceResolutionFilterHealthTest.java`
- `src/test/java/com/beaconculinary/api/orders/AdjustmentIdempotencyIntegrationTests.java`
- `src/test/java/com/beaconculinary/api/orders/ClientIdRequiredIntegrationTests.java`
- `src/test/java/com/beaconculinary/api/orders/OrderEventsAfterCommitIntegrationTests.java`
- `src/test/java/com/beaconculinary/api/orders/OrderIdempotencyIntegrationTests.java`
- `src/test/java/com/beaconculinary/api/orders/OrderNumberConcurrencyIntegrationTests.java`
- `src/test/java/com/beaconculinary/api/orders/OrderNumberingFailureTest.java`
- `src/test/java/com/beaconculinary/api/orders/RequestFingerprintTest.java`

### Modified (21)

- `src/main/resources/application.yaml`
- `src/main/java/com/beaconculinary/api/auth/SecurityConfig.java`
- `src/main/java/com/beaconculinary/api/bar/BarService.java`
- `src/main/java/com/beaconculinary/api/board/PublicBoardService.java`
- `src/main/java/com/beaconculinary/api/common/LoggingFilter.java`
- `src/main/java/com/beaconculinary/api/devices/DeviceResolutionFilter.java`
- `src/main/java/com/beaconculinary/api/endofday/TradingDayLock.java`
- `src/main/java/com/beaconculinary/api/kitchen/KitchenService.java`
- `src/main/java/com/beaconculinary/api/orders/CreateOrderAdjustmentRequest.java`
- `src/main/java/com/beaconculinary/api/orders/CreateOrderRequest.java`
- `src/main/java/com/beaconculinary/api/orders/Order.java`
- `src/main/java/com/beaconculinary/api/orders/OrderAdjustment.java`
- `src/main/java/com/beaconculinary/api/orders/OrderAdjustmentRepository.java`
- `src/main/java/com/beaconculinary/api/orders/OrderAdjustmentService.java`
- `src/main/java/com/beaconculinary/api/orders/OrderController.java`
- `src/main/java/com/beaconculinary/api/orders/OrderRepository.java`
- `src/main/java/com/beaconculinary/api/orders/OrderRuleException.java`
- `src/main/java/com/beaconculinary/api/orders/OrderService.java`
- `src/main/java/com/beaconculinary/api/orders/OrderStationService.java`
- `src/main/java/com/beaconculinary/api/orders/OrderStatusEventPublisher.java`
- `src/main/java/com/beaconculinary/api/orders/StationViewChangedEvent.java`

No applied migration was edited. `flyway:clean` was never run. Every test ran against the test database.
