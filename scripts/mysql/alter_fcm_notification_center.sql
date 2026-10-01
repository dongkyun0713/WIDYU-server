-- #696 / LLD-0062. W2의 notification_type/data_payload DDL 적용 뒤 실행한다.
-- 운영 적용 전 SHOW CREATE TABLE로 기존 컬럼·FK·인덱스와 중복을 확인한다.
-- fcm_category는 native ENUM일 수 있으므로 변경하지 않는다.
ALTER TABLE fcm_notification
    ADD COLUMN event_id VARCHAR(40) NULL,
    ADD COLUMN type VARCHAR(48) NULL,
    ADD COLUMN deep_link VARCHAR(255) NULL,
    ADD COLUMN entity_id VARCHAR(255) NULL,
    ADD COLUMN senior_id BIGINT NULL,
    ADD COLUMN actor_display_name VARCHAR(255) NULL,
    ADD COLUMN expires_at DATETIME(6) NULL,
    ADD COLUMN retention_policy_version VARCHAR(32) NULL,
    ADD COLUMN push_eligible BOOLEAN NULL,
    ADD COLUMN read_at DATETIME(6) NULL,
    MODIFY COLUMN member_fcm_token_id BIGINT NULL,
    ADD CONSTRAINT uk_fcm_notification_recipient_event UNIQUE (recipient_member_id, event_id);

ALTER TABLE fcm_outbox
    ADD COLUMN notification_id BIGINT NULL,
    ADD CONSTRAINT fk_fcm_outbox_notification
        FOREIGN KEY (notification_id) REFERENCES fcm_notification (id);
