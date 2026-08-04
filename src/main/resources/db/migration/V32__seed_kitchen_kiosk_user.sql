-- Seeds a single shared kiosk user for the kitchen station (Stage 2.1) — not a
-- per-staff-member account. Logs in via the existing PIN-login endpoint, exactly like a
-- cashier does, just with different route access (KITCHEN/ADMIN-only /kitchen/**).
--
--   Kitchen Station — PIN 4321 (documented for local/dev use only)
--
-- email/password aren't a real login path for this account (it's PIN-only), but both columns
-- are NOT NULL — filled with the same "no known plaintext" filler hash AuthService already
-- uses for its constant-time PIN-miss comparison, so no new secret is being minted here.
INSERT INTO [users] ([name], [email], [password], [role], [pin_hash], [active]) VALUES
    ('Kitchen Station', 'kitchen@canteen.local',
     '$2a$10$2zAMRFEuq50y/bjUAAFpbO1K2wJjlSChL3EZPBO3FwvpR2HMhik7S',
     'KITCHEN',
     '$2a$10$aMMXijFTSgMF4y8sbShFNOIZ8Esk/yqoGbuIe7z0F7sZxKbS3iVpK',
     1);
