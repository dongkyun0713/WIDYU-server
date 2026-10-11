-- #698: apply before deploying the entities (production uses ddl-auto=validate).
-- MySQL 8: backfill each family's existing connection order; membership id breaks timestamp ties.
ALTER TABLE family_membership ADD COLUMN sort_order INT NULL;
ALTER TABLE family ADD COLUMN family_order_revision BIGINT NOT NULL DEFAULT 0;

CREATE TEMPORARY TABLE family_membership_sort_backfill AS
SELECT id, ROW_NUMBER() OVER (PARTITION BY family_id ORDER BY connected_at, id) - 1 AS sort_order
FROM family_membership;

UPDATE family_membership fm
JOIN family_membership_sort_backfill b ON b.id = fm.id
SET fm.sort_order = b.sort_order;

DROP TEMPORARY TABLE family_membership_sort_backfill;
ALTER TABLE family_membership MODIFY COLUMN sort_order INT NOT NULL;
