-- Seeds one STOCK_CLERK and one STOCK_ADMIN for exercising the new role boundaries locally.
--
--   Stock Clerk — email: stockclerk@canteen.local   password: 654321   PIN: 654321
--   Stock Admin — email: stockadmin@canteen.local   password: 654321   PIN: 654321
--
-- Reuses existing valid BCrypt hashes of "654321" already seeded on other rows (V34) — a BCrypt
-- hash embeds its own salt, so reusing one for the same plaintext verifies correctly; no new
-- secret is being minted here, same convention V32 already used for its filler hash.
INSERT INTO [users] ([name], [email], [password], [role], [pin_hash], [active]) VALUES
    ('Stock Clerk', 'stockclerk@canteen.local',
     '$2a$10$.b8cbkPEmPqaUgpZGR2YkubXAqbaUcN8XxycgzoVqj0QdtguXvMnC',
     'STOCK_CLERK',
     '$2a$10$Puj4pEQxfA54SfrUdNBcd.q6Bwdw2MSMeaT.d.pWF1jrUCgtBJCC2',
     1),
    ('Stock Admin', 'stockadmin@canteen.local',
     '$2a$10$.b8cbkPEmPqaUgpZGR2YkubXAqbaUcN8XxycgzoVqj0QdtguXvMnC',
     'STOCK_ADMIN',
     '$2a$10$Puj4pEQxfA54SfrUdNBcd.q6Bwdw2MSMeaT.d.pWF1jrUCgtBJCC2',
     1);
