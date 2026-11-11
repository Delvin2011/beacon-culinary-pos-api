-- WS1-1 Phase 2 (enforce mode): the lock a failed attempt triggered.
--
-- Set only on the failure that reaches a LOCK rule's threshold (PASSWORD_ACCOUNT,
-- PIN_ACCOUNT_CEILING), for known and unknown identifiers alike. For an unknown email or cashier
-- there is no user_auth_state row, so these columns are the lock state: the identifier is locked
-- while its latest lock row has locked_until > now, and escalation follows the same rule as for
-- real accounts. For known users user_auth_state stays authoritative; the columns are an audit copy.
--
-- lock_level 3 means "until a manager unlocks it" (locked_until = 9999-12-31 23:59:59.9999999),
-- as in user_auth_state. UTC, written by the application clock.

ALTER TABLE [login_attempts] ADD
    [lock_level]   INT       NULL CONSTRAINT [CK_login_attempts_lock_level] CHECK ([lock_level] BETWEEN 1 AND 3),
    [locked_until] DATETIME2 NULL;
