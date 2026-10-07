-- POS Oversight A5: supports the /admin/shifts date-range list and the per-shift activity
-- lookups. None of these existed before (only idx_shifts_cashier_status and
-- IX_order_adjustments_order_id).
CREATE INDEX [idx_shifts_opened_at] ON [shifts]([opened_at]);
CREATE INDEX [idx_orders_shift] ON [orders]([shift_id]);
CREATE INDEX [idx_order_adjustments_shift] ON [order_adjustments]([shift_id]);
