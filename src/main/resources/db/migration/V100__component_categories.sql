-- Component categories: a flat list (Meat, Chicken, Fish, Starch, ...) so the component catalogue
-- can be grouped and searched. A component's category is optional (NULL = uncategorised): the CSV
-- recipe import and older clients create components without one.
-- Split across GO batches: the UPDATE references a column added in the batch before it.

CREATE TABLE [component_categories] (
    [id]         BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [name]       NVARCHAR(50) NOT NULL,
    [sort_order] INT          NOT NULL CONSTRAINT [DF_component_categories_sort_order] DEFAULT 0,
    [active]     BIT          NOT NULL CONSTRAINT [DF_component_categories_active] DEFAULT 1,
    CONSTRAINT [UQ_component_categories_name] UNIQUE ([name])
);

INSERT INTO [component_categories] ([name], [sort_order]) VALUES
    ('Meat',                 1),
    ('Chicken',              2),
    ('Fish',                 3),
    ('Starch',               4),
    ('Vegetables & relishes', 5),
    ('Sauces & gravies',     6),
    ('Desserts & fruit',     7);
GO

ALTER TABLE [component_catalog] ADD
    [category_id] BIGINT NULL CONSTRAINT [FK_component_catalog_category] FOREIGN KEY REFERENCES [component_categories]([id]);
CREATE INDEX [IX_component_catalog_category_id] ON [component_catalog]([category_id]);
GO

-- Existing components, matched by name; anything not listed stays uncategorised.
UPDATE c SET [category_id] = cat.[id]
FROM [component_catalog] c
JOIN (VALUES
    ('Beef Stew',                'Meat'),
    ('Mince Curry (protein)',    'Meat'),
    ('Mince Filling',            'Meat'),
    ('Sausage (protein)',        'Meat'),
    ('Wors (protein)',           'Meat'),
    ('Roast Chicken (protein)',  'Chicken'),
    ('Fish (battered, protein)', 'Fish'),
    ('Breyani Rice',             'Starch'),
    ('Chips (starch)',           'Starch'),
    ('Mash Potato (starch)',     'Starch'),
    ('Pap (starch)',             'Starch'),
    ('Pasta (starch)',           'Starch'),
    ('Rice (starch)',            'Starch'),
    ('Spaghetti (starch)',       'Starch'),
    ('Tomato & Onion Relish',    'Vegetables & relishes'),
    ('Beef Curry Sauce',         'Sauces & gravies'),
    ('Bolognaise Sauce',         'Sauces & gravies'),
    ('Chicken Curry Sauce',      'Sauces & gravies'),
    ('Creamy Sauce',             'Sauces & gravies'),
    ('Gravy',                    'Sauces & gravies'),
    ('Onion Gravy',              'Sauces & gravies'),
    ('Stew Sauce',               'Sauces & gravies'),
    ('Tomato & Onion Gravy',     'Sauces & gravies'),
    ('Apple Fruit',              'Desserts & fruit'),
    ('Egg Custard Topping',      'Desserts & fruit')
) AS m ([component], [category]) ON m.[component] = c.[name]
JOIN [component_categories] cat ON cat.[name] = m.[category];
