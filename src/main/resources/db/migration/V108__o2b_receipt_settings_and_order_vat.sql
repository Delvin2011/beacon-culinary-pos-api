-- O2b: receipt data (docs/2026-11-17-o2b-receipt-data-api-contract.md).
--
-- 1. receipt_settings: one row of business details for printed receipts. Every value may be empty;
--    nothing customer-specific is seeded. WS2-5 will key it by tenant instead of id = 1.
--    updated_by has no foreign key to users (users are hard-deleted, as for devices in V105); the
--    name is kept beside it.
-- 2. orders.vat_rate and orders.vat_included_amount: the VAT included in the sale, stored once at
--    creation (rule in orders/VatRule.java and section 3 of the contract). Existing orders stay NULL.

CREATE TABLE [receipt_settings] (
    [id]              TINYINT       NOT NULL CONSTRAINT [PK_receipt_settings] PRIMARY KEY
                          CONSTRAINT [CK_receipt_settings_single] CHECK ([id] = 1),
    [business_name]   NVARCHAR(100) NULL,
    [address_line_1]  NVARCHAR(100) NULL,
    [address_line_2]  NVARCHAR(100) NULL,
    [address_line_3]  NVARCHAR(100) NULL,
    [address_line_4]  NVARCHAR(100) NULL,
    [phone]           NVARCHAR(50)  NULL,
    [vat_number]      NVARCHAR(20)  NULL,
    [footer_text]     NVARCHAR(500) NULL,
    [show_vat_line]   BIT           NOT NULL CONSTRAINT [DF_receipt_settings_show_vat_line] DEFAULT 0,
    [updated_at]      DATETIME2     NULL,
    [updated_by]      BIGINT        NULL,
    [updated_by_name] NVARCHAR(255) NULL
);
GO

-- The single row, empty.
INSERT INTO [receipt_settings] ([id]) VALUES (1);
GO

ALTER TABLE [orders] ADD
    [vat_rate]            DECIMAL(6, 4)  NULL,
    [vat_included_amount] DECIMAL(10, 2) NULL;
GO
