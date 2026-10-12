-- Receivable Management - Story 11.1 Create Receivable.
-- Reference DDL for receivable.
--
-- One row per dispatched invoice: created when an APPROVED invoice's
-- "Send to Client" attempt reaches SENT (invoice_delivery.status = 'SENT').
-- invoice_amount and the opening outstanding_balance are both a frozen copy
-- of invoice.grand_total - never recalculated from billing configuration,
-- schedules or tax data. invoice_number, client, project, currency,
-- invoice_date and due_date are frozen copies of the same invoice row.
--
-- uk_receivable_invoice is the final guard for "one invoice -> at most one
-- receivable"; fk_receivable_invoice guarantees every receivable references
-- a real invoice.
--
-- status: OUTSTANDING on creation. PARTIALLY_PAID / SETTLED are reserved;
-- the formal lifecycle belongs to a later story.
-- The outstanding view is decided by outstanding_balance > 0, not status;
-- zero-balance rows are excluded from it but never deleted.
-- outstanding_balance can never go below 0
-- (chk_receivable_outstanding_non_negative).
--
-- NOTE: This project has no Flyway/Liquibase migration runner; schema is
-- managed via `spring.jpa.hibernate.ddl-auto=update`, which creates this
-- table from the Receivable entity on startup. This script is a reference
-- only, kept in sync with the entity (dialect: MySQL). It deliberately does
-- NOT backfill receivables for invoices that were already sent before this
-- table existed.

CREATE TABLE IF NOT EXISTS receivable (
    receivable_id          CHAR(36)        NOT NULL,
    version                BIGINT          NOT NULL,
    invoice_id             CHAR(36)        NOT NULL,
    invoice_number         VARCHAR(30)     NOT NULL,
    client_id              CHAR(36)        NOT NULL,
    client_name            VARCHAR(255)        NULL,
    project_id             BIGINT          NOT NULL,
    project_name           VARCHAR(255)        NULL,
    currency_code          VARCHAR(10)         NULL,
    invoice_date           DATE            NOT NULL,
    -- NULL when the invoice's own due_date is NULL (unresolved payment term).
    due_date               DATE                NULL,
    invoice_amount         DECIMAL(19, 2)  NOT NULL,
    outstanding_balance    DECIMAL(19, 2)  NOT NULL,
    status                 VARCHAR(20)     NOT NULL,
    created_at             DATETIME(6)     NOT NULL,
    updated_at             DATETIME(6)         NULL,
    PRIMARY KEY (receivable_id),
    CONSTRAINT uk_receivable_invoice UNIQUE (invoice_id),
    CONSTRAINT fk_receivable_invoice FOREIGN KEY (invoice_id) REFERENCES invoice (invoice_id),
    CONSTRAINT chk_receivable_outstanding_non_negative CHECK (outstanding_balance >= 0),
    INDEX idx_receivable_client (client_id),
    INDEX idx_receivable_status (status)
);

-- Story 11.2: for a receivable table that already existed before the CHECK
-- was added (ddl-auto=update does not add CHECK constraints to existing
-- tables). Enforced by MySQL 8.0.16+.
-- ALTER TABLE receivable
--     ADD CONSTRAINT chk_receivable_outstanding_non_negative CHECK (outstanding_balance >= 0);
