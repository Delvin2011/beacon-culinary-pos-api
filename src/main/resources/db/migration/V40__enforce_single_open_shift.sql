-- Stage 2.5: widens Stage 1.1's per-cashier "one open shift" rule to a system-wide one — the
-- till itself can only have one open shift, regardless of who owns it. Enforced at the DB
-- level (filtered unique index) rather than only in application code, closing the race-
-- condition gap an app-level check alone can't — same principle as the conditional-update
-- pattern used for stock decrement (OrderService) and the extras_adjusted guard (Stage 2.6).
CREATE UNIQUE INDEX [idx_shifts_single_open] ON [shifts]([status]) WHERE [status] = 'OPEN';
