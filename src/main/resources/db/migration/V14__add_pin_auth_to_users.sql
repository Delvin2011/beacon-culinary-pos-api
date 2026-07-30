-- Adds PIN-based auth (Stage 1.1) alongside the existing email/password login.
-- Frontend team: PINs below are documented for local/dev use only.
--
--   Cashier A — id 1, PIN 1234
--   Admin     — id 2, PIN 9999
--   Cashier B — id 3, PIN 5678 (second cashier, used to test shift-ownership rules)

ALTER TABLE [users] ADD [pin_hash] NVARCHAR(255) NULL;
ALTER TABLE [users] ADD [active] BIT NOT NULL CONSTRAINT DF_users_active DEFAULT 1;
GO

UPDATE [users] SET [pin_hash] = '$2a$10$cTyfkFaCjzTFWTl/VseVM.vQ.BR7GXEDYe1rcJIDgj9DR8OVbDzKi' WHERE [email] = 'cashier@canteen.local';
UPDATE [users] SET [pin_hash] = '$2a$10$P/naYJSa7yAcUNVjjJXSj.yhLFAPUImCsJC9IUQcR/lJ5A8emUC/y' WHERE [email] = 'admin@canteen.local';

INSERT INTO [users] ([name], [email], [password], [role], [pin_hash], [active]) VALUES
    ('Canteen Cashier B', 'cashierb@canteen.local', '$2a$10$96Wiensp48gJIQ16F76Pk.1R6OV1KOMQ.KD.wT/HHM7QH2A9fr7gm', 'CASHIER', '$2a$10$Ma5e5VQSoD5lGBx.NLIVLuMsw0nMAwx8X0CJfwIBRFN5BAXJ.H.Ju', 1);
