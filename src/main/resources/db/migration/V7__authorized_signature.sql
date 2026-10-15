-- =========================================================
-- Authorized signatory signature
-- =========================================================
-- company_signature_asset holds the uploaded images (immutable rows).
-- company_profile points at the current one; invoice freezes the reference
-- at generation. Both reference columns are nullable and NOT backfilled:
-- existing invoices keep NULL and render without a signature.
-- NOTE: manual script - the application has no migration runner. Where Hibernate ddl-auto=update already
-- created these objects, do NOT run it (see docs/authorized-signature.md section 3).
-- =========================================================

CREATE TABLE IF NOT EXISTS company_signature_asset (
    signature_asset_id CHAR(36)     NOT NULL,
    company_profile_id CHAR(36)     NOT NULL,
    content_type       VARCHAR(50)  NOT NULL,
    size_bytes         BIGINT       NOT NULL,
    sha256             CHAR(64)     NOT NULL,
    content            LONGBLOB     NOT NULL,
    created_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (signature_asset_id),
    KEY idx_signature_asset_profile (company_profile_id)
);

ALTER TABLE company_profile
    ADD COLUMN authorized_signature_reference VARCHAR(36) NULL;

ALTER TABLE invoice
    ADD COLUMN seller_signature_reference VARCHAR(36) NULL;
