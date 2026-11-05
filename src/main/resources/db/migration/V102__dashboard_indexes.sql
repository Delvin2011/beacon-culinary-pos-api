-- Back-office dashboard: the live sections (Right now, Needs attention) look up open and ready
-- orders by status, which grows with every order ever rung. No new tables.
CREATE INDEX [IX_orders_status_created_at] ON [orders]([status], [created_at]);
