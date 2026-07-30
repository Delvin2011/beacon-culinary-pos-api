CREATE TABLE [component_catalog] (
    [id]          BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [name]        NVARCHAR(50)   NOT NULL,
    [extra_price] DECIMAL(10, 2) NOT NULL,
    [active]      BIT            NOT NULL DEFAULT 1
);
