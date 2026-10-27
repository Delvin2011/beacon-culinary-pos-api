# Public board — finished orders drop off

A `DONE` order stays on the public board for `board.done-display-minutes` (default **10**) after
it was finished, then drops off. `PENDING` and `IN_PROGRESS` orders never expire. No migration.

## Setting
```yaml
board:
  done-display-minutes: ${BOARD_DONE_DISPLAY_MINUTES:10}
```
Set `BOARD_DONE_DISPLAY_MINUTES` in `.env` or the environment; it is read at startup (restart to
change). `0` removes an order as soon as it's `DONE`. A negative value stops the app starting.

## `GET /public/board/today`
```json
{
  "orders": [
    { "orderNumber": 12, "status": "IN_PROGRESS" },
    { "orderNumber": 11, "status": "DONE",
      "doneAt": "2026-10-27T10:35:00Z", "removeAt": "2026-10-27T10:45:00Z" }
  ],
  "serverTime": "2026-10-27T10:41:07Z",
  "doneDisplayMinutes": 10
}
```
- Expired `DONE` orders (`serverTime` ≥ `removeAt`) are already left out.
- `doneAt` / `removeAt` (UTC) appear only on `DONE` orders.

## `GET /public/board/stream`
The `STATUS_CHANGED` event to `DONE` carries the same `doneAt` / `removeAt`. Other events are
unchanged.

## What the board does
1. On load (and on every stream reconnect), call `/today`, and work out
   `offset = serverTime − local clock`.
2. For each `DONE` order, remove it at `removeAt` (`setTimeout(removeAt − (Date.now() + offset))`).
   Do the same when a `DONE` event arrives on the stream.
3. Other status changes behave as before (e.g. a voided order disappears straight away).

Nothing else changes in the payload: still no order id, lines or cashier data.
