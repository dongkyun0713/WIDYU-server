-- #608: apply before deploying the outbox application.
-- Legacy notification recipients remain NULL; no owner backfill or deletion.
ALTER TABLE fcm_notification
    ADD COLUMN recipient_member_id BIGINT NULL,
    ADD CONSTRAINT fk_fcm_notification_recipient FOREIGN KEY (recipient_member_id) REFERENCES member (id),
    ADD INDEX idx_fcm_notification_recipient (recipient_member_id, id);

CREATE TABLE fcm_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    recipient_member_id BIGINT NOT NULL,
    member_fcm_token_id BIGINT NOT NULL,
    related_member_id BIGINT NULL,
    family_id BIGINT NULL,
    title VARCHAR(255) NULL,
    body VARCHAR(255) NULL,
    image VARCHAR(255) NULL,
    scheme VARCHAR(255) NULL,
    fcm_category VARCHAR(32) NOT NULL,
    emergency BOOLEAN NOT NULL,
    state VARCHAR(24) NOT NULL,
    attempts INT NOT NULL,
    fence BIGINT NOT NULL,
    available_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    lease_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_fcm_outbox_recipient FOREIGN KEY (recipient_member_id) REFERENCES member (id),
    CONSTRAINT fk_fcm_outbox_token FOREIGN KEY (member_fcm_token_id) REFERENCES member_fcm_token (id),
    INDEX idx_fcm_outbox_due (state, available_at, lease_until)
);
