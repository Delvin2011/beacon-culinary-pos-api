-- carts table
CREATE TABLE [carts] (
    [id] UNIQUEIDENTIFIER NOT NULL DEFAULT NEWID() PRIMARY KEY,
    [date_created] DATE NOT NULL DEFAULT CAST(GETDATE() AS DATE)
    );

-- cart_items table
CREATE TABLE [cart_items] (
    [id] BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [cart_id] UNIQUEIDENTIFIER NOT NULL,
    [product_id] BIGINT NOT NULL,
    [quantity] INT NOT NULL DEFAULT 1,
    CONSTRAINT [UQ_cart_items_cart_product] UNIQUE ([cart_id], [product_id]),
    CONSTRAINT [FK_cart_items_carts] FOREIGN KEY ([cart_id]) REFERENCES [carts]([id]) ON DELETE CASCADE,
    CONSTRAINT [FK_cart_items_products] FOREIGN KEY ([product_id]) REFERENCES [products]([id]) ON DELETE CASCADE
    );