-- Insert categories
INSERT INTO categories (name)
VALUES
    ('Beer & Ciders'),
    ('Spirits & Mixers'),
    ('Wines'),
    ('Soft Drinks');

-- Insert products
INSERT INTO products (name, price, description, category_id)
VALUES
-- Beer & Ciders
('Castle Lager Single', 32.00, 'Castle Lager single bottle.', 1),
('Castle Lager Pack (6)', 175.00, 'Castle Lager 6 pack.', 1),
('Savanna Dry Single', 38.00, 'Savanna Dry cider single.', 1),
('Windhoek Draught Single', 45.00, 'Windhoek Draught beer single.', 1),
('Carling Black Label Single', 30.00, 'Black Label lager.', 1),
('Hunters Dry Single', 36.00, 'Hunters Dry cider.', 1),
('Amstel Lager Single', 34.00, 'Amstel premium lager.', 1),
('Heineken Single', 36.00, 'Heineken international lager.', 1),

-- Spirits & Mixers
('Smirnoff Vodka Shot', 25.00, 'Single vodka shot.', 2),
('Jameson Whiskey Shot', 35.00, 'Irish whiskey shot.', 2),
('Jack Daniels Shot', 38.00, 'Tennessee whiskey shot.', 2),
('Captain Morgan & Coke', 45.00, 'Rum and coke mix.', 2),
('Gin & Tonic', 40.00, 'Gin with tonic water.', 2),
('Brandy & Coke', 38.00, 'Popular SA mix.', 2),
('Jagermeister Shot', 30.00, 'Herbal liqueur shot.', 2),

-- Wines
('House Red Wine Glass', 45.00, 'Glass of house red wine.', 3),
('House White Wine Glass', 45.00, 'Glass of house white wine.', 3),
('House Red Wine Bottle', 180.00, 'Bottle of red wine.', 3),
('House White Wine Bottle', 180.00, 'Bottle of white wine.', 3),
('Rose Wine Glass', 48.00, 'Glass of rose wine.', 3),

-- Soft Drinks
('Coca-Cola', 20.00, '330ml Coke.', 4),
('Coke Zero', 20.00, 'Sugar-free Coke.', 4),
('Sprite', 20.00, 'Lemon-lime soda.', 4),
('Fanta Orange', 20.00, 'Orange soda.', 4),
('Stoney Ginger Beer', 22.00, 'Ginger beer soft drink.', 4),
('Red Bull', 35.00, 'Energy drink.', 4),
('Still Water', 15.00, '500ml bottled water.', 4),
('Sparkling Water', 18.00, 'Sparkling mineral water.', 4);
