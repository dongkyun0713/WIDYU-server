CREATE TABLE medication_proof_image_deletion_task (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, member_id BIGINT NOT NULL, object_key VARCHAR(1024) NOT NULL,
 status VARCHAR(20) NOT NULL, retry_count INT NOT NULL, processing_attempt INT NOT NULL DEFAULT 0,
 lease_expires_at DATETIME NULL, next_retry_at DATETIME NULL,
 last_error_type VARCHAR(100) NULL, completed_at DATETIME NULL, failed_at DATETIME NULL,
 created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,
 INDEX idx_proof_image_deletion_retry (status, next_retry_at)
);
