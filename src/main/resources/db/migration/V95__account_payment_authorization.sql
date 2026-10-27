-- Account payments at the till need an admin's approval, like a discount or a complimentary meal.
-- authorized_by is the approving admin on an ACCOUNT payment row; NULL on CASH/CARD rows and on
-- account orders placed before this rule.
ALTER TABLE [order_payments] ADD [authorized_by] BIGINT NULL
    CONSTRAINT [FK_order_payments_authorized_by] FOREIGN KEY REFERENCES [users]([id]);
