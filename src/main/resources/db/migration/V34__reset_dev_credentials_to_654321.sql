-- Resets local/dev credentials so every login secret is the same value for now: 654321.
-- Frontend team: log in with the credentials below.
--
--   Cashier A — email: cashier@canteen.local    password: 654321   PIN: 654321
--   Cashier B — email: cashierb@canteen.local   password: 654321   PIN: 654321
--   Admin     — email: admin@canteen.local      password: 654321   PIN: 654321
--   Kitchen   — PIN-only login                                     PIN: 654321
--
-- Passwords/PINs are BCrypt-hashed below; the plaintext values are documented here
-- for local/dev convenience only — never do this for real user data.

UPDATE [users] SET [password] = '$2a$10$t54ikJ1ILSzs7HCFXWs80.CXsNI0LuVIfiwuByzjlqsaNqvNIa1kK',
                    [pin_hash] = '$2a$10$Puj4pEQxfA54SfrUdNBcd.q6Bwdw2MSMeaT.d.pWF1jrUCgtBJCC2'
    WHERE [email] = 'cashier@canteen.local';

UPDATE [users] SET [password] = '$2a$10$nmVG6lluj9bHkUd09eLm/OWethkeq1Sloxx0ZJXde.B8Uhhn88tPq',
                    [pin_hash] = '$2a$10$e3MexyCtNio7GQazSpxvieaZ5FikpS9qv6uOCJ8eCeZucYDIukQh2'
    WHERE [email] = 'cashierb@canteen.local';

UPDATE [users] SET [password] = '$2a$10$.b8cbkPEmPqaUgpZGR2YkubXAqbaUcN8XxycgzoVqj0QdtguXvMnC',
                    [pin_hash] = '$2a$10$ezSlIKsmB/kQhXwFgCdhR.cBXSZJcjlR5CCNvg4d0SQrcTeupU1z6'
    WHERE [email] = 'admin@canteen.local';

UPDATE [users] SET [pin_hash] = '$2a$10$XQ5DcCVTglulhjs2jE1fPuYC3.bkGsx8UssniEQuYjhlfpvIshD6a'
    WHERE [email] = 'kitchen@canteen.local';
