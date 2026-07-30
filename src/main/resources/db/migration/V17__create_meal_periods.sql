CREATE TABLE [meal_periods] (
    [id]         BIGINT IDENTITY(1,1) NOT NULL PRIMARY KEY,
    [name]       NVARCHAR(50) NOT NULL,
    [start_time] TIME         NOT NULL,
    [end_time]   TIME         NOT NULL
);

INSERT INTO [meal_periods] ([name], [start_time], [end_time]) VALUES
    ('Breakfast', '07:00', '10:00'),
    ('Lunch',     '12:00', '14:30');
