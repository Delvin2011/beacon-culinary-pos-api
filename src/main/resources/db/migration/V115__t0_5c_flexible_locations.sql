-- T0-5c: flexible stock locations (docs/2026-11-21-t0-5c-locations-api-contract.md).
--
-- 1. locations.kind (PRIMARY, SECONDARY), sort_order and audit columns. Main Store becomes the
--    PRIMARY, Kitchen a SECONDARY, any other row a SECONDARY. If either name is missing the
--    migration stops with a message instead of guessing.
-- 2. Constraints the API relies on:
--    - exactly one PRIMARY, always active (filtered unique index, check, and a trigger that
--      refuses any statement leaving the table without exactly one PRIMARY);
--    - a name is unique among active locations (the column's collation ignores case).
-- 3. location_assignments: the location each job uses. PRODUCTION (daily planning's issue
--    destination) and the sale-time source per station (STATION_KITCHEN, STATION_BAR,
--    STATION_NONE). All seeded to Kitchen, so behaviour is unchanged. The service checks each
--    points at an active SECONDARY.
-- 4. location_changes: insert-only history of every CREATE, UPDATE, DEACTIVATE, ACTIVATE and
--    ASSIGN.
-- 5. stock_requests.location_id now also names an ISSUE's requesting (destination) location.
--    Every existing ISSUE went to Kitchen; they are backfilled to it.
--
-- Audit user columns have no foreign key to users, like V105, V113 and V114: users are hard-deleted.
-- Split across GO batches: a batch can't reference a column added earlier in the same batch, and
-- CREATE TRIGGER must be the only statement in its batch.

-- 1. Columns --------------------------------------------------------------------------------------

ALTER TABLE [locations] ADD
    [kind]            NVARCHAR(10)  NULL,
    [sort_order]      INT           NULL,
    [created_at]      DATETIME2     NULL,
    [created_by]      BIGINT        NULL,
    [created_by_name] NVARCHAR(255) NULL,
    [updated_at]      DATETIME2     NULL,
    [updated_by]      BIGINT        NULL,
    [updated_by_name] NVARCHAR(255) NULL;
GO

DECLARE @problem NVARCHAR(400) = NULL;
IF NOT EXISTS (SELECT 1 FROM [locations] WHERE [name] = 'Main Store')
    SET @problem = N'T0-5c: location "Main Store" not found; it becomes the primary location. Rename the receiving location back to "Main Store" before V115.';
ELSE IF NOT EXISTS (SELECT 1 FROM [locations] WHERE [name] = 'Kitchen')
    SET @problem = N'T0-5c: location "Kitchen" not found; it becomes the default production and station location. Rename it back to "Kitchen" before V115.';
ELSE IF EXISTS (SELECT 1 FROM [locations] WHERE [name] IN ('Main Store', 'Kitchen') AND [active] = 0)
    SET @problem = N'T0-5c: "Main Store" or "Kitchen" is inactive; reactivate it before V115.';
ELSE IF (SELECT COUNT(*) FROM [locations] WHERE [name] IN ('Main Store', 'Kitchen')) > 2
    SET @problem = N'T0-5c: more than one location is called "Main Store" or "Kitchen"; rename the duplicate before V115.';
ELSE
    SET @problem = (
        SELECT TOP 1 CONCAT(N'T0-5c: more than one active location is called ', [name], N'; rename one before V115.')
        FROM [locations] WHERE [active] = 1 GROUP BY [name] HAVING COUNT(*) > 1 ORDER BY [name]);
IF @problem IS NOT NULL
    THROW 51000, @problem, 1;
GO

UPDATE [locations] SET [kind] = 'PRIMARY', [sort_order] = 10 WHERE [name] = 'Main Store';
UPDATE [locations] SET [kind] = 'SECONDARY', [sort_order] = 20 WHERE [name] = 'Kitchen';

UPDATE l
SET [kind] = 'SECONDARY', [sort_order] = 20 + ordered.[rn] * 10
FROM [locations] l
JOIN (
    SELECT [id], ROW_NUMBER() OVER (ORDER BY [id]) AS [rn]
    FROM [locations] WHERE [kind] IS NULL
) ordered ON ordered.[id] = l.[id];
GO

ALTER TABLE [locations] ALTER COLUMN [kind] NVARCHAR(10) NOT NULL;
ALTER TABLE [locations] ALTER COLUMN [sort_order] INT NOT NULL;
GO

-- 2. Constraints ----------------------------------------------------------------------------------

ALTER TABLE [locations] ADD
    CONSTRAINT [CK_locations_kind] CHECK ([kind] IN ('PRIMARY', 'SECONDARY')),
    CONSTRAINT [CK_locations_primary_active] CHECK ([kind] <> 'PRIMARY' OR [active] = 1);
GO

CREATE UNIQUE INDEX [UX_locations_one_primary] ON [locations] ([kind]) WHERE [kind] = 'PRIMARY';
GO

CREATE UNIQUE INDEX [UX_locations_active_name] ON [locations] ([name]) WHERE [active] = 1;
GO

-- The unique index stops a second PRIMARY; this stops none (deleting it, or changing its kind).
CREATE TRIGGER [trg_locations_exactly_one_primary] ON [locations]
AFTER INSERT, UPDATE, DELETE
AS
BEGIN
    IF (SELECT COUNT(*) FROM [locations] WHERE [kind] = 'PRIMARY') <> 1
        THROW 51000, 'There must be exactly one primary location.', 1;
END;
GO

-- 3. Assignments ----------------------------------------------------------------------------------

CREATE TABLE [location_assignments] (
    [role]            NVARCHAR(20)  NOT NULL CONSTRAINT [PK_location_assignments] PRIMARY KEY
                          CONSTRAINT [CK_location_assignments_role]
                          CHECK ([role] IN ('PRODUCTION', 'STATION_KITCHEN', 'STATION_BAR', 'STATION_NONE')),
    [location_id]     BIGINT        NOT NULL
                          CONSTRAINT [FK_location_assignments_locations] REFERENCES [locations] ([id]),
    [updated_at]      DATETIME2     NULL,
    [updated_by]      BIGINT        NULL,
    [updated_by_name] NVARCHAR(255) NULL
);
GO

INSERT INTO [location_assignments] ([role], [location_id])
SELECT r.[role], k.[id]
FROM (VALUES ('PRODUCTION'), ('STATION_KITCHEN'), ('STATION_BAR'), ('STATION_NONE')) r ([role])
CROSS JOIN (SELECT [id] FROM [locations] WHERE [name] = 'Kitchen') k;
GO

-- 4. History (insert-only) ------------------------------------------------------------------------

CREATE TABLE [location_changes] (
    [id]              BIGINT IDENTITY(1, 1) NOT NULL CONSTRAINT [PK_location_changes] PRIMARY KEY,
    [location_id]     BIGINT        NOT NULL
                          CONSTRAINT [FK_location_changes_locations] REFERENCES [locations] ([id]),
    [action]          NVARCHAR(12)  NOT NULL
                          CONSTRAINT [CK_location_changes_action]
                          CHECK ([action] IN ('CREATE', 'UPDATE', 'DEACTIVATE', 'ACTIVATE', 'ASSIGN')),
    -- ASSIGN only: which job now uses this location; old_location_id is the one it replaced.
    [role]            NVARCHAR(20)  NULL,
    [old_location_id] BIGINT        NULL,
    [old_name]        NVARCHAR(50)  NULL,
    [old_sort_order]  INT           NULL,
    [old_active]      BIT           NULL,
    [new_name]        NVARCHAR(50)  NOT NULL,
    [new_sort_order]  INT           NOT NULL,
    [new_active]      BIT           NOT NULL,
    [changed_at]      DATETIME2     NOT NULL,
    [changed_by]      BIGINT        NOT NULL,
    [changed_by_name] NVARCHAR(255) NOT NULL,
    CONSTRAINT [CK_location_changes_role] CHECK (
        ([action] = 'ASSIGN' AND [role] IS NOT NULL AND [role] IN ('PRODUCTION', 'STATION_KITCHEN', 'STATION_BAR', 'STATION_NONE'))
        OR ([action] <> 'ASSIGN' AND [role] IS NULL AND [old_location_id] IS NULL)),
    -- CREATE and ASSIGN have no "before" for the location itself; the other actions have one.
    CONSTRAINT [CK_location_changes_before] CHECK (
        ([action] IN ('CREATE', 'ASSIGN') AND [old_name] IS NULL AND [old_sort_order] IS NULL AND [old_active] IS NULL)
        OR ([action] NOT IN ('CREATE', 'ASSIGN') AND [old_name] IS NOT NULL AND [old_sort_order] IS NOT NULL
            AND [old_active] IS NOT NULL))
);
GO

-- Newest first, overall and per location (GET /admin/locations/history[?locationId=]).
CREATE INDEX [IX_location_changes_location_changed_at]
    ON [location_changes] ([location_id], [changed_at] DESC, [id] DESC);
GO

CREATE TRIGGER [trg_location_changes_insert_only] ON [location_changes]
AFTER UPDATE, DELETE
AS
BEGIN
    THROW 51000, 'Location changes are insert-only.', 1;
END;
GO

-- 5. ISSUE requests name their destination -------------------------------------------------------

UPDATE [stock_requests]
SET [location_id] = (SELECT [id] FROM [locations] WHERE [name] = 'Kitchen')
WHERE [request_type] = 'ISSUE' AND [location_id] IS NULL;
GO

-- Deactivation looks for open requests and stock takes at a location.
CREATE INDEX [IX_stock_requests_location_status] ON [stock_requests] ([location_id], [status]);
GO

CREATE INDEX [IX_stock_takes_location_status] ON [stock_takes] ([location_id], [status]);
GO
