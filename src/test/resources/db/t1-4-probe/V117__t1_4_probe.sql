-- T1-4, TESTS ONLY (src/test/resources; never in the app jar): a migration after the newest real one, so
-- MigrateCustomersCommandIntegrationTests can show a real update and, where this table already exists, a failure.
CREATE TABLE [t1_4_probe] (
    [id] INT NOT NULL CONSTRAINT [PK_t1_4_probe] PRIMARY KEY
);
