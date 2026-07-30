CREATE TABLE [meal_catalog_components] (
    [id]                    BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [meal_catalog_id]       BIGINT NOT NULL,
    [component_catalog_id]  BIGINT NOT NULL,
    CONSTRAINT [FK_meal_catalog_components_meal_catalog] FOREIGN KEY ([meal_catalog_id]) REFERENCES [meal_catalog]([id]) ON DELETE CASCADE,
    CONSTRAINT [FK_meal_catalog_components_component_catalog] FOREIGN KEY ([component_catalog_id]) REFERENCES [component_catalog]([id])
);

CREATE INDEX [IX_meal_catalog_components_meal_catalog_id] ON [meal_catalog_components]([meal_catalog_id]);
