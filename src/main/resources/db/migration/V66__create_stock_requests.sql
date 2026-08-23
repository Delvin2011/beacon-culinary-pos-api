-- Stage 5 Revision: a request to move (ISSUE) or write off (WASTE) stock, submitted by a
-- STOCK_CLERK (or STOCK_ADMIN/ADMIN acting directly) and authorized by a STOCK_ADMIN/ADMIN via
-- POST /stock-requests/{id}/action. When source = DAILY_PLANNING, daily_plan_date/
-- daily_plan_period_id carry the traceability that ingredient_requirement_confirmations (Stage
-- 5 Part C) used to — confirm-ingredient-requirements creates one of these instead now, and no
-- longer writes that table going forward (left in place, unused, for its historical rows).
CREATE TABLE [stock_requests] (
    [id]                    BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [request_type]          NVARCHAR(10)  NOT NULL,
    [source]                NVARCHAR(20)  NOT NULL,
    [requested_by]          BIGINT        NOT NULL,
    [requested_at]          DATETIME2     NOT NULL DEFAULT SYSUTCDATETIME(),
    [status]                NVARCHAR(20)  NOT NULL DEFAULT 'REQUESTED',
    [actioned_by]           BIGINT        NULL,
    [actioned_at]           DATETIME2     NULL,
    [daily_plan_date]       DATE          NULL,
    [daily_plan_period_id]  BIGINT        NULL,
    CONSTRAINT [FK_stock_requests_requested_by] FOREIGN KEY ([requested_by]) REFERENCES [users]([id]),
    CONSTRAINT [FK_stock_requests_actioned_by] FOREIGN KEY ([actioned_by]) REFERENCES [users]([id]),
    CONSTRAINT [FK_stock_requests_daily_plan_period] FOREIGN KEY ([daily_plan_period_id]) REFERENCES [meal_periods]([id]),
    CONSTRAINT [CK_stock_requests_request_type] CHECK ([request_type] IN ('ISSUE', 'WASTE')),
    CONSTRAINT [CK_stock_requests_source] CHECK ([source] IN ('DAILY_PLANNING', 'MANUAL')),
    CONSTRAINT [CK_stock_requests_status] CHECK ([status] IN ('REQUESTED', 'PARTIALLY_ACTIONED', 'ACTIONED', 'REJECTED'))
);
