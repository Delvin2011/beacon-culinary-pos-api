-- Stage 5 Part B: a component's raw-ingredient bill of materials, written as a batch (e.g. "10
-- portions needs 1.5kg rice") — one recipe per component_catalog row (flat, no nested recipes).
CREATE TABLE [recipes] (
    [id]                     BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [component_catalog_id]   BIGINT NOT NULL UNIQUE,
    [batch_size]             INT    NOT NULL,
    CONSTRAINT [FK_recipes_component_catalog] FOREIGN KEY ([component_catalog_id]) REFERENCES [component_catalog]([id]),
    CONSTRAINT [CK_recipes_batch_size] CHECK ([batch_size] > 0)
);

CREATE TABLE [recipe_lines] (
    [id]             BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [recipe_id]      BIGINT NOT NULL,
    [ingredient_id]  BIGINT NOT NULL,
    [quantity]       DECIMAL(10, 4) NOT NULL,
    CONSTRAINT [FK_recipe_lines_recipe] FOREIGN KEY ([recipe_id]) REFERENCES [recipes]([id]),
    CONSTRAINT [FK_recipe_lines_ingredient] FOREIGN KEY ([ingredient_id]) REFERENCES [ingredients]([id]),
    CONSTRAINT [CK_recipe_lines_quantity] CHECK ([quantity] > 0)
);

CREATE INDEX [IX_recipe_lines_recipe_id] ON [recipe_lines]([recipe_id]);
