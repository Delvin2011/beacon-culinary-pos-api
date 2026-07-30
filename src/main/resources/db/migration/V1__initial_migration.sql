-- addresses table
CREATE TABLE [addresses] (
    [id] BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [street] NVARCHAR(255) NOT NULL,
    [city] NVARCHAR(255) NOT NULL,
    [state] NVARCHAR(255) NOT NULL,
    [zip] NVARCHAR(255) NOT NULL,
    [user_id] BIGINT NOT NULL
    );

-- categories table
CREATE TABLE [categories] (
    [id] TINYINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [name] NVARCHAR(255) NOT NULL
    );

-- products table
CREATE TABLE [products] (
    [id] BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [name] NVARCHAR(255) NOT NULL,
    [price] DECIMAL(10,2) NOT NULL,
    [description] NVARCHAR(MAX) NOT NULL,
    [category_id] TINYINT NULL
    );

-- profiles table
CREATE TABLE [profiles] (
    [id] BIGINT NOT NULL PRIMARY KEY,
    [bio] NVARCHAR(MAX) NULL,
    [phone_number] NVARCHAR(15) NULL,
    [date_of_birth] DATE NULL,
    [loyalty_points] INT DEFAULT 0 NULL
    );

-- users table
CREATE TABLE [users] (
    [id] BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [name] NVARCHAR(255) NOT NULL,
    [email] NVARCHAR(255) NOT NULL,
    [password] NVARCHAR(255) NOT NULL
    );

-- wishlist table
CREATE TABLE [wishlist] (
    [product_id] BIGINT NOT NULL,
    [user_id] BIGINT NOT NULL,
     CONSTRAINT [PK_wishlist] PRIMARY KEY ([product_id], [user_id])
    );

-- Foreign keys
ALTER TABLE [addresses]
    ADD CONSTRAINT [FK_addresses_users] FOREIGN KEY ([user_id]) REFERENCES [users]([id]);

ALTER TABLE [products]
    ADD CONSTRAINT [FK_products_categories] FOREIGN KEY ([category_id]) REFERENCES [categories]([id]);

ALTER TABLE [wishlist]
    ADD CONSTRAINT [FK_wishlist_products] FOREIGN KEY ([product_id]) REFERENCES [products]([id]) ON DELETE CASCADE;

ALTER TABLE [wishlist]
    ADD CONSTRAINT [FK_wishlist_users] FOREIGN KEY ([user_id]) REFERENCES [users]([id]);

ALTER TABLE [profiles]
    ADD CONSTRAINT [FK_profiles_users] FOREIGN KEY ([id]) REFERENCES [users]([id]);

-- Indexes
CREATE INDEX [IX_addresses_user_id] ON [addresses]([user_id]);
CREATE INDEX [IX_products_category_id] ON [products]([category_id]);
CREATE INDEX [IX_wishlist_user_id] ON [wishlist]([user_id]);