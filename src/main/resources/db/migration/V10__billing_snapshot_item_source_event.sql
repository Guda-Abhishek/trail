-- =========================================================
-- Expense handoff event identity on snapshot items
-- =========================================================
-- XMS hands the same expense (lineItemId = source_reference_id) off more than
-- once when it is corrected; each handoff event has its own syncId. Storing the
-- syncId of the event a line was billed from lets AR tell a repeat of the same
-- handoff (idempotent, rejected as already billed) from a correction (never
-- added as a new charge). The correction reason, handoff time and superseded
-- syncIds are kept in the existing source_metadata JSON. Existing rows stay NULL.
-- Cross-snapshot duplicate prevention still keys on lineItemId, not syncId.
-- NOTE: manual script - the application has no migration runner. Where Hibernate ddl-auto=update already
-- created this column, do NOT run it (check information_schema first).
-- =========================================================

ALTER TABLE billing_snapshot_item
    ADD COLUMN source_event_id VARCHAR(100) NULL;
