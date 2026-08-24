-- Stage 5 Revision: one ingredient's requested quantity within a stock_requests header.
-- actioned_quantity stays NULL until a STOCK_ADMIN/ADMIN authorizes the request; reason is only
-- meaningful for WASTE-type requests (free text, same purpose as waste_entries.reason).
CREATE TABLE [stock_request_lines] (
    [id]                  BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [stock_request_id]    BIGINT         NOT NULL,
    [ingredient_id]       BIGINT         NOT NULL,
    [requested_quantity]  DECIMAL(10, 4) NOT NULL,
    [actioned_quantity]   DECIMAL(10, 4) NULL,
    [reason]              NVARCHAR(255)  NULL,
    CONSTRAINT [FK_stock_request_lines_stock_request] FOREIGN KEY ([stock_request_id]) REFERENCES [stock_requests]([id]),
    CONSTRAINT [FK_stock_request_lines_ingredient] FOREIGN KEY ([ingredient_id]) REFERENCES [ingredients]([id]),
    CONSTRAINT [CK_stock_request_lines_requested_quantity] CHECK ([requested_quantity] > 0)
);

CREATE INDEX [IX_stock_request_lines_stock_request_id] ON [stock_request_lines]([stock_request_id]);
