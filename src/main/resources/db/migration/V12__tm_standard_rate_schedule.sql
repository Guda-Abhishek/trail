-- =========================================================
-- T&M Standard Rate Schedule: dated discount periods + snapshot calculation audit
-- =========================================================
-- billing_tm_rate_card keeps the base rate; each discount period is a row in billing_tm_rate_discount.
-- billing_snapshot_item gets the frozen calculation of each timesheet line (rate = net rate, amount = final
-- line amount, both unchanged in meaning), and billing_tm_rate_card gets include_base_rate_in_invoice. Existing rows stay NULL, and NULL include_in_invoice means included.
--
-- MySQL 8.0.16+ (CHECK constraints are enforced from that version).
-- NOTE: manual script - the application has no migration runner.
-- IDEMPOTENT: every step checks information_schema first, so it is safe to run
--   * on a database where Hibernate (spring.jpa.hibernate.ddl-auto=update) already created the table/columns
--     (it then only adds what is missing: the CHECK constraints Hibernate never creates), and
--   * more than once.
-- It never drops, renames or rewrites anything.
--
-- Types follow what Hibernate 6 generates for MySQL: java.util.UUID ids are BINARY(16) (see the note in
-- billing_acquisition_orphan_snapshot_backfill.sql), so the foreign key to billing_tm_rate_card.rate_card_id
-- is BINARY(16) too - a CHAR(36) column would make the foreign key fail.
-- Overlapping periods are rejected by the service under a row lock on the parent rate card; a unique
-- constraint cannot express date-range overlap in MySQL.
--
-- Run:  mysql --database=<ar_db> < V12__tm_standard_rate_schedule.sql
-- Verify afterwards with the queries at the bottom of this file.
-- =========================================================

CREATE TABLE IF NOT EXISTS billing_tm_rate_discount (
    discount_id        BINARY(16)    NOT NULL,
    rate_card_id       BINARY(16)    NOT NULL,
    period_name        VARCHAR(150)  NOT NULL,
    discount_type      VARCHAR(20)   NOT NULL,
    discount_value     DECIMAL(12,2) NOT NULL,
    effective_from     DATE          NOT NULL,
    effective_to       DATE          NOT NULL,
    notes              VARCHAR(500)  NULL,
    include_in_invoice BIT(1)        NOT NULL,
    is_active          BIT(1)        NOT NULL,
    created_at         DATETIME(6)   NULL,
    updated_at         DATETIME(6)   NULL,
    PRIMARY KEY (discount_id),
    CONSTRAINT fk_tm_rate_discount_card FOREIGN KEY (rate_card_id) REFERENCES billing_tm_rate_card (rate_card_id)
);

DROP PROCEDURE IF EXISTS tm_v12_apply;

DELIMITER //
CREATE PROCEDURE tm_v12_apply()
BEGIN
    DECLARE db VARCHAR(64) DEFAULT DATABASE();

    -- ---- index on billing_tm_rate_discount ----
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = db AND table_name = 'billing_tm_rate_discount'
                     AND index_name = 'idx_tm_rate_discount_card_dates') THEN
        CREATE INDEX idx_tm_rate_discount_card_dates
            ON billing_tm_rate_discount (rate_card_id, effective_from, effective_to);
    END IF;

    -- ---- CHECK constraints (Hibernate does not create these) ----
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints
                   WHERE table_schema = db AND table_name = 'billing_tm_rate_discount'
                     AND constraint_name = 'chk_tm_rate_discount_type') THEN
        ALTER TABLE billing_tm_rate_discount
            ADD CONSTRAINT chk_tm_rate_discount_type CHECK (discount_type IN ('PERCENTAGE', 'FIXED'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints
                   WHERE table_schema = db AND table_name = 'billing_tm_rate_discount'
                     AND constraint_name = 'chk_tm_rate_discount_value') THEN
        ALTER TABLE billing_tm_rate_discount
            ADD CONSTRAINT chk_tm_rate_discount_value CHECK (discount_value > 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints
                   WHERE table_schema = db AND table_name = 'billing_tm_rate_discount'
                     AND constraint_name = 'chk_tm_rate_discount_dates') THEN
        ALTER TABLE billing_tm_rate_discount
            ADD CONSTRAINT chk_tm_rate_discount_dates CHECK (effective_from <= effective_to);
    END IF;

    -- ---- billing_tm_rate_card: base-rate inclusion (NULL = included, for existing rows) ----
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = db
                   AND table_name = 'billing_tm_rate_card' AND column_name = 'include_base_rate_in_invoice') THEN
        ALTER TABLE billing_tm_rate_card ADD COLUMN include_base_rate_in_invoice BIT(1) NULL;
    END IF;

    -- ---- billing_snapshot_item calculation columns ----
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = db
                   AND table_name = 'billing_snapshot_item' AND column_name = 'base_rate') THEN
        ALTER TABLE billing_snapshot_item ADD COLUMN base_rate DECIMAL(19,2) NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = db
                   AND table_name = 'billing_snapshot_item' AND column_name = 'rate_currency') THEN
        ALTER TABLE billing_snapshot_item ADD COLUMN rate_currency VARCHAR(10) NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = db
                   AND table_name = 'billing_snapshot_item' AND column_name = 'rate_period') THEN
        ALTER TABLE billing_snapshot_item ADD COLUMN rate_period VARCHAR(20) NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = db
                   AND table_name = 'billing_snapshot_item' AND column_name = 'discount_type') THEN
        ALTER TABLE billing_snapshot_item ADD COLUMN discount_type VARCHAR(20) NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = db
                   AND table_name = 'billing_snapshot_item' AND column_name = 'discount_value') THEN
        ALTER TABLE billing_snapshot_item ADD COLUMN discount_value DECIMAL(19,2) NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = db
                   AND table_name = 'billing_snapshot_item' AND column_name = 'discount_amount') THEN
        ALTER TABLE billing_snapshot_item ADD COLUMN discount_amount DECIMAL(19,2) NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = db
                   AND table_name = 'billing_snapshot_item' AND column_name = 'discount_period_id') THEN
        ALTER TABLE billing_snapshot_item ADD COLUMN discount_period_id VARCHAR(36) NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = db
                   AND table_name = 'billing_snapshot_item' AND column_name = 'discount_period_name') THEN
        ALTER TABLE billing_snapshot_item ADD COLUMN discount_period_name VARCHAR(150) NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = db
                   AND table_name = 'billing_snapshot_item' AND column_name = 'discount_period_start') THEN
        ALTER TABLE billing_snapshot_item ADD COLUMN discount_period_start DATE NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = db
                   AND table_name = 'billing_snapshot_item' AND column_name = 'discount_period_end') THEN
        ALTER TABLE billing_snapshot_item ADD COLUMN discount_period_end DATE NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = db
                   AND table_name = 'billing_snapshot_item' AND column_name = 'include_in_invoice') THEN
        ALTER TABLE billing_snapshot_item ADD COLUMN include_in_invoice BIT(1) NULL;
    END IF;
END//
DELIMITER ;

CALL tm_v12_apply();
DROP PROCEDURE tm_v12_apply;

-- ---------------------------------------------------------
-- Verification (expect 1 table, 11 columns, 3 checks, 1 index):
--   SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
--      AND table_name = 'billing_tm_rate_card' AND column_name = 'include_base_rate_in_invoice';  -- 1
--   SELECT COUNT(*) FROM information_schema.tables
--    WHERE table_schema = DATABASE() AND table_name = 'billing_tm_rate_discount';
--   SELECT COUNT(*) FROM information_schema.columns
--    WHERE table_schema = DATABASE() AND table_name = 'billing_snapshot_item'
--      AND column_name IN ('base_rate','rate_currency','rate_period','discount_type','discount_value',
--          'discount_amount','discount_period_id','discount_period_name','discount_period_start',
--          'discount_period_end','include_in_invoice');
--   SELECT constraint_name FROM information_schema.table_constraints
--    WHERE table_schema = DATABASE() AND table_name = 'billing_tm_rate_discount'
--      AND constraint_type = 'CHECK';
--   SHOW INDEX FROM billing_tm_rate_discount;
-- ---------------------------------------------------------
