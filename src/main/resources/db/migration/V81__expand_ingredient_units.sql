ALTER TABLE [ingredients] DROP CONSTRAINT [CK_ingredients_unit];

ALTER TABLE [ingredients] ADD CONSTRAINT [CK_ingredients_unit]
    CHECK ([unit] IN ('KG', 'LITRE', 'EACH', 'LOAF', 'ROLL', 'PACK', 'DOZEN', 'BOX', 'PAIR'));
