-- ============================================================================
-- Seed data for local/dev use. Frontend team: log in with the credentials below.
--
--   Cashier — email: cashier@canteen.local   password: Cashier@123
--   Admin   — email: admin@canteen.local     password: Admin@123
--
-- Passwords are BCrypt-hashed below; the plaintext values are documented here
-- for local/dev convenience only — never do this for real user data.
-- ============================================================================

-- 1. Seed users
INSERT INTO [users] ([name], [email], [password], [role]) VALUES
    ('Canteen Cashier', 'cashier@canteen.local', '$2a$10$pENk4gCHmY6ok0.qZOfI5ug4oLO7V69BuqgSbNEzc0PM.qEDbtjBS', 'CASHIER'),
    ('Canteen Admin',   'admin@canteen.local',   '$2a$10$b6irLVDlcIhBF1b7wnwnBOmqtvbnV7Lzf.y6vK/6mFJXE5LMzy0p.', 'ADMIN');

-- 2. Seed menu categories (id 1-4) — Beverages is tax-exempt (0.0000) to prove
--    the rate isn't hardcoded, and to give a non-default rate for testing.
INSERT INTO [menu_categories] ([name], [tax_rate]) VALUES
    ('Breakfast', 0.0500),
    ('Lunch',     0.1000),
    ('Beverages', 0.0000),
    ('Snacks',    0.1000);

-- 3. Seed menu items (id 1-12)
-- Breakfast (category 1) — window 06:00-10:30, closed outside typical breakfast hours
INSERT INTO [menu_items] ([name], [description], [category_id], [is_veg], [is_prepackaged], [all_day], [start_time], [end_time], [tax_rate_override]) VALUES
    ('Idli Sambar',  'Steamed rice cakes served with lentil soup.', 1, 1, 0, 0, '06:00:00', '10:30:00', NULL), -- id 1
    ('Masala Dosa',  'Crispy rice crepe with spiced potato filling.', 1, 1, 0, 0, '06:00:00', '10:30:00', NULL), -- id 2
-- Lunch (category 2) — window 12:00-15:00
    ('Veg Thali',           'Full vegetarian meal with rice, dal, sabzi, and roti.', 2, 1, 0, 0, '12:00:00', '15:00:00', NULL), -- id 3
    ('Chicken Biryani',     'Fragrant basmati rice layered with spiced chicken.',    2, 0, 0, 0, '12:00:00', '15:00:00', NULL), -- id 4
    ('Paneer Butter Masala','Cottage cheese in a rich buttery tomato gravy.',        2, 1, 0, 1, NULL,        NULL,       0.1800), -- id 5, available all day, overrides Lunch's 10% rate
-- Beverages (category 3) — all-day, tax-exempt
    ('Filter Coffee',         'South Indian filter coffee.',        3, 1, 0, 1, NULL, NULL, NULL), -- id 6
    ('Cold Coffee',           'Iced blended coffee.',                3, 1, 0, 1, NULL, NULL, NULL), -- id 7
    ('Packaged Mango Juice',  '200ml sealed mango juice carton.',    3, 1, 1, 1, NULL, NULL, NULL), -- id 8, prepackaged, low stock
    ('Bottled Water',         '500ml sealed bottled water.',         3, 1, 1, 1, NULL, NULL, NULL), -- id 9, prepackaged, OUT OF STOCK
-- Snacks (category 4) — mostly all-day, one deliberately closed for testing
    ('Samosa',              'Deep-fried pastry with spiced potato filling.', 4, 1, 0, 1, NULL,        NULL,       NULL), -- id 10
    ('Packaged Chips',      'Sealed 30g potato chips packet.',               4, 1, 1, 1, NULL,        NULL,       NULL), -- id 11, prepackaged, low stock
    ('Midnight Snack Box',  'Late-night snack box — only served overnight.', 4, 0, 0, 0, '00:00:00',  '01:00:00', NULL); -- id 12, deliberately closed during normal test hours

-- 4. Seed menu item variants
-- Single-variant items ("Standard")
INSERT INTO [menu_item_variants] ([menu_item_id], [name], [price], [stock_quantity]) VALUES
    (1,  'Standard', 40.00,  0), -- Idli Sambar
    (3,  'Standard', 120.00, 0), -- Veg Thali
    (5,  'Standard', 110.00, 0), -- Paneer Butter Masala
    (6,  'Standard', 20.00,  0), -- Filter Coffee
    (8,  '200ml',    35.00,  4), -- Packaged Mango Juice — low stock, easy to exhaust
    (9,  '500ml',    15.00,  0), -- Bottled Water — OUT OF STOCK on purpose
    (10, 'Standard', 15.00,  0), -- Samosa
    (11, 'Standard', 20.00,  5), -- Packaged Chips — low stock
    (12, 'Standard', 60.00,  0); -- Midnight Snack Box

-- Multi-variant items (Half/Full, Regular/Large)
INSERT INTO [menu_item_variants] ([menu_item_id], [name], [price], [stock_quantity]) VALUES
    (2, 'Half', 50.00, 0), -- Masala Dosa
    (2, 'Full', 80.00, 0),
    (4, 'Half', 150.00, 0), -- Chicken Biryani
    (4, 'Full', 280.00, 0),
    (7, 'Regular', 40.00, 0), -- Cold Coffee
    (7, 'Large',   60.00, 0);

-- 5. Modifier groups
-- Mutually-exclusive: pick exactly one spice level for Chicken Biryani (item 4)
INSERT INTO [modifier_groups] ([menu_item_id], [name], [min_selected], [max_selected]) VALUES
    (4, 'Spice Level', 1, 1); -- group id 1
-- Flat add-ons: pick 0-3 extras for Cold Coffee (item 7)
INSERT INTO [modifier_groups] ([menu_item_id], [name], [min_selected], [max_selected]) VALUES
    (7, 'Add-ons', 0, 3); -- group id 2

-- 6. Modifiers
INSERT INTO [modifiers] ([modifier_group_id], [name], [price_delta]) VALUES
    (1, 'Mild',   0.00),
    (1, 'Medium', 0.00),
    (1, 'Hot',    0.00),
    (2, 'Extra Shot',    10.00),
    (2, 'Whipped Cream', 15.00),
    (2, 'Choco Syrup',   10.00);
