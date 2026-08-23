-- Stage 5.2.1: locations that raw-ingredient stock can physically sit at. Exactly two rows for
-- this stage — Main Store (the receiving/procurement location) and Kitchen (where recipe-based
-- daily-planning consumption happens). No UI to add more yet; a third location is future work.
CREATE TABLE [locations] (
    [id]     BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [name]   NVARCHAR(50) NOT NULL,
    [active] BIT NOT NULL DEFAULT 1
);

INSERT INTO [locations] ([name]) VALUES ('Main Store'), ('Kitchen');
