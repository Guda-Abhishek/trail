-- =========================================================
-- Expense currency conversion - snapshot item audit fields
-- =========================================================
-- billing_snapshot_item.amount stays the BILLED amount, in the snapshot's
-- currency (unchanged meaning; every consumer keeps working). When an XMS
-- expense was in a different currency, these nullable columns keep what XMS
-- supplied and how it was converted. They are written once at acquisition
-- and never recomputed. Existing rows (and same-currency expenses) stay NULL.
-- NOTE: manual script - the application has no migration runner. Where Hibernate ddl-auto=update already
-- created these columns, do NOT run it.
-- =========================================================

ALTER TABLE billing_snapshot_item
    ADD COLUMN source_amount        DECIMAL(19,4) NULL,
    ADD COLUMN source_currency      VARCHAR(10)   NULL,
    ADD COLUMN target_currency      VARCHAR(10)   NULL,
    ADD COLUMN exchange_rate        DECIMAL(19,8) NULL,
    ADD COLUMN exchange_rate_date   DATE          NULL,
    ADD COLUMN exchange_rate_source VARCHAR(255)  NULL;
