-- LLD-0066: Apply after the W3 notification center DDL and before deploying this entity mapping.
ALTER TABLE fcm_notification
    ADD COLUMN senior_display_name VARCHAR(255) NULL,
    ADD COLUMN remaining_locked_count INT NULL;
