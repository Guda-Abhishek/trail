-- =========================================================
-- Finance-maintained exchange rates (Phase 2 - storage only)
-- =========================================================
-- rate = units of target_currency per 1 unit of source_currency, effective on
-- effective_date. One rate per (source, target, date): the unique key makes an
-- ambiguous rate impossible. NO rows are seeded - Finance supplies and approves
-- every rate. Nothing in AR reads this table until an ExchangeRateProvider
-- backed by it is approved and implemented.
-- NOTE: manual script - the application has no migration runner. Where Hibernate ddl-auto=update already
-- created this table, do NOT run it.
-- =========================================================

CREATE TABLE IF NOT EXISTS currency_exchange_rate (
    exchange_rate_id CHAR(36)       NOT NULL,
    source_currency  VARCHAR(10)    NOT NULL,
    target_currency  VARCHAR(10)    NOT NULL,
    rate             DECIMAL(19,8)  NOT NULL,
    effective_date   DATE           NOT NULL,
    rate_source      VARCHAR(255)   NOT NULL,
    approved_by      VARCHAR(100)   NOT NULL,
    approved_at      DATETIME(6)    NOT NULL,
    created_at       DATETIME(6)    NOT NULL,
    PRIMARY KEY (exchange_rate_id),
    UNIQUE KEY uk_currency_exchange_rate_pair_date (source_currency, target_currency, effective_date),
    CONSTRAINT ck_currency_exchange_rate_positive CHECK (rate > 0),
    CONSTRAINT ck_currency_exchange_rate_pair CHECK (source_currency <> target_currency)
);
