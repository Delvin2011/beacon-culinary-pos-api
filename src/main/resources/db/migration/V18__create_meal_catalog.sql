CREATE TABLE [meal_catalog] (
    [id]          BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [name]        NVARCHAR(100)  NOT NULL,
    [description] NVARCHAR(255)  NULL,
    [price]       DECIMAL(10, 2) NOT NULL,
    [active]      BIT            NOT NULL DEFAULT 1
);
