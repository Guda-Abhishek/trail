-- =========================================================
-- currency_exchange_rate - add the CHECK constraints the live table lacks
-- =========================================================
-- V9 declared "rate > 0" and "source_currency <> target_currency", but where the table was created by
-- Hibernate ddl-auto=update only the primary key and uk_currency_exchange_rate_pair_date exist (verified
-- on the live schema), so rows inserted by SQL could bypass the entity checks.
--
-- NOTE: manual script - the application has no migration runner (schema is managed by Hibernate
-- ddl-auto=update plus hand-applied scripts). Apply it manually, once, per environment:
--
--   1. Check what exists (the script is only needed if this returns no ck_currency_exchange_rate_* rows):
--        SELECT constraint_name FROM information_schema.check_constraints
--         WHERE constraint_schema = DATABASE() AND constraint_name LIKE 'ck_currency_exchange_rate%';
--   2. Check no existing row would violate them (both must return 0 rows; the table is empty until Finance
--      loads approved rates, so this normally is trivially true):
--        SELECT * FROM currency_exchange_rate WHERE rate <= 0 OR source_currency = target_currency;
--   3. Run the two ALTER statements below. MySQL 8.0.16+ enforces CHECK constraints (live server is 8.4).
--      If a constraint of that name already exists the statement fails harmlessly - do not drop and re-add.
--   4. Re-run step 1 to confirm both constraints are present.
-- Rollback: ALTER TABLE currency_exchange_rate DROP CHECK ck_currency_exchange_rate_positive; (and _pair).
-- This script loads NO rates and chooses no policy.
-- =========================================================

ALTER TABLE currency_exchange_rate
    ADD CONSTRAINT ck_currency_exchange_rate_positive CHECK (rate > 0);

ALTER TABLE currency_exchange_rate
    ADD CONSTRAINT ck_currency_exchange_rate_pair CHECK (source_currency <> target_currency);
