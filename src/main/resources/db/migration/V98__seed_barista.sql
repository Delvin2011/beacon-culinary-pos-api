-- Part B: one BARISTA for exercising the role locally (PIN only; the email is only for lookup).
--   Barista — PIN: 654321 (bar display). Reuses the BCrypt hash of "654321" (V69/V89 convention).
INSERT INTO [users] ([name], [email], [password], [role], [pin_hash], [active]) VALUES
    ('Canteen Barista', 'barista@canteen.local', NULL, 'BARISTA',
     '$2a$10$Puj4pEQxfA54SfrUdNBcd.q6Bwdw2MSMeaT.d.pWF1jrUCgtBJCC2', 1);
