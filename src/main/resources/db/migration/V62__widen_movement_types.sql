-- CONSUMED_FOR_PREP is renamed to CONSUMED — same event (recipe-based Kitchen usage via daily
-- planning), now explicitly scoped to Kitchen via location_id rather than one undifferentiated
-- pool. ISSUED is added to the allowed set for a later stage's Main Store -> Kitchen issue
-- movement (Stage 5.2.1 Section 3) — nothing produces it yet.
ALTER TABLE [ingredient_stock_movements] DROP CONSTRAINT [CK_ingredient_stock_movements_movement_type];

UPDATE [ingredient_stock_movements] SET [movement_type] = 'CONSUMED' WHERE [movement_type] = 'CONSUMED_FOR_PREP';

ALTER TABLE [ingredient_stock_movements]
    ADD CONSTRAINT [CK_ingredient_stock_movements_movement_type] CHECK ([movement_type] IN
        ('RECEIVED', 'ISSUED', 'CONSUMED', 'WASTED', 'STOCK_TAKE_ADJUSTMENT'));
