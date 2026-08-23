-- Stage 5 Revision: two new roles for the stock domain specifically — STOCK_CLERK (request-only)
-- and STOCK_ADMIN (authorizes/performs stock actions). ADMIN remains a superset of both.
ALTER TABLE [users] DROP CONSTRAINT [CK_users_role];

ALTER TABLE [users] WITH CHECK ADD CONSTRAINT [CK_users_role]
    CHECK ([role] IN ('USER', 'CASHIER', 'ADMIN', 'KITCHEN', 'STOCK_CLERK', 'STOCK_ADMIN'));
