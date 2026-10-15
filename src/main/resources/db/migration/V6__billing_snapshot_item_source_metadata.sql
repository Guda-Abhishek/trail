-- =========================================================
-- Expense Management (XMS) integration - snapshot item audit fields
-- =========================================================
-- Existing billing_snapshot_item columns already carry the line itself
-- (item_type=EXPENSE, source_reference_id=XMS lineItemId, amount=costBasis,
-- work_date=expenseDate). These two nullable columns hold the origin system
-- and a frozen JSON copy of the source's audit fields. Existing rows stay NULL.
-- =========================================================

ALTER TABLE billing_snapshot_item
    ADD COLUMN source_system VARCHAR(20) NULL,
    ADD COLUMN source_metadata TEXT NULL;
