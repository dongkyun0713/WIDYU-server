-- 탈퇴 회원의 소셜 연동 해제 작업 (LLD-0059). refresh_token은 앱에서 AES-GCM으로 암호화해 저장한다.
CREATE TABLE social_unlink_task (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, member_id BIGINT NOT NULL, provider VARCHAR(20) NOT NULL,
 oauth_id VARCHAR(255) NULL, refresh_token VARCHAR(2048) NULL,
 status VARCHAR(20) NOT NULL, attempt_count INT NOT NULL, next_retry_at DATETIME NOT NULL,
 last_error_type VARCHAR(100) NULL, completed_at DATETIME NULL, failed_at DATETIME NULL,
 created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,
 INDEX idx_social_unlink_due (status, next_retry_at)
);
