-- menu_categories table
CREATE TABLE [menu_categories] (
    [id]       TINYINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [name]     NVARCHAR(255)  NOT NULL,
    [tax_rate] DECIMAL(5, 4)  NOT NULL
    );

-- menu_items table
CREATE TABLE [menu_items] (
    [id]                 BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [name]               NVARCHAR(255)  NOT NULL,
    [description]        NVARCHAR(MAX)  NULL,
    [category_id]        TINYINT        NOT NULL,
    [is_veg]             BIT            NOT NULL DEFAULT 0,
    [is_prepackaged]     BIT            NOT NULL DEFAULT 0,
    [all_day]            BIT            NOT NULL DEFAULT 1,
    [start_time]         TIME           NULL,
    [end_time]           TIME           NULL,
    [tax_rate_override]  DECIMAL(5, 4)  NULL,
    CONSTRAINT [FK_menu_items_categories] FOREIGN KEY ([category_id]) REFERENCES [menu_categories]([id]),
    CONSTRAINT [CK_menu_items_time_window] CHECK (
        [all_day] = 1 OR ([start_time] IS NOT NULL AND [end_time] IS NOT NULL)
        )
    );

-- menu_item_variants table
CREATE TABLE [menu_item_variants] (
    [id]              BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [menu_item_id]    BIGINT         NOT NULL,
    [name]            NVARCHAR(100)  NOT NULL,
    [price]           DECIMAL(10, 2) NOT NULL,
    [stock_quantity]  INT            NOT NULL DEFAULT 0,
    CONSTRAINT [FK_menu_item_variants_items] FOREIGN KEY ([menu_item_id]) REFERENCES [menu_items]([id]) ON DELETE CASCADE,
    CONSTRAINT [UQ_menu_item_variants_item_name] UNIQUE ([menu_item_id], [name]),
    CONSTRAINT [CK_menu_item_variants_stock_non_negative] CHECK ([stock_quantity] >= 0)
    );

-- modifier_groups table
CREATE TABLE [modifier_groups] (
    [id]            BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [menu_item_id]  BIGINT        NOT NULL,
    [name]          NVARCHAR(100) NOT NULL,
    [min_selected]  INT           NOT NULL DEFAULT 0,
    [max_selected]  INT           NOT NULL DEFAULT 1,
    CONSTRAINT [FK_modifier_groups_items] FOREIGN KEY ([menu_item_id]) REFERENCES [menu_items]([id]) ON DELETE CASCADE,
    CONSTRAINT [CK_modifier_groups_min_max] CHECK ([min_selected] >= 0 AND [max_selected] >= [min_selected])
    );

-- modifiers table
CREATE TABLE [modifiers] (
    [id]                 BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [modifier_group_id]  BIGINT         NOT NULL,
    [name]               NVARCHAR(100)  NOT NULL,
    [price_delta]        DECIMAL(10, 2) NOT NULL DEFAULT 0,
    CONSTRAINT [FK_modifiers_groups] FOREIGN KEY ([modifier_group_id]) REFERENCES [modifier_groups]([id]) ON DELETE CASCADE
    );

-- Indexes
CREATE INDEX [IX_menu_items_category_id] ON [menu_items]([category_id]);
CREATE INDEX [IX_menu_item_variants_menu_item_id] ON [menu_item_variants]([menu_item_id]);
CREATE INDEX [IX_modifier_groups_menu_item_id] ON [modifier_groups]([menu_item_id]);
CREATE INDEX [IX_modifiers_modifier_group_id] ON [modifiers]([modifier_group_id]);
