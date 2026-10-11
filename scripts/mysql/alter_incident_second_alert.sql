-- #739 LLD-0081. 새 애플리케이션 전환 전 적용하고 구 앱과 혼합 쓰기 창을 두지 않는다.
ALTER TABLE incident
    ADD COLUMN second_alert_due_at_ms BIGINT NULL,
    ADD COLUMN second_alert_sent_at_ms BIGINT NULL,
    ADD COLUMN second_alert_cancelled_at_ms BIGINT NULL,
    ADD INDEX idx_incident_second_alert_pending (second_alert_sent_at_ms, second_alert_due_at_ms);

CREATE TABLE incident_guardian_response (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    incident_id BIGINT NOT NULL,
    guardian_member_id BIGINT NOT NULL,
    response_type VARCHAR(20) NOT NULL,
    responded_at_ms BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_incident_guardian_response_kind UNIQUE (incident_id, guardian_member_id, response_type),
    CONSTRAINT fk_incident_guardian_response_incident FOREIGN KEY (incident_id) REFERENCES incident (incident_id),
    CONSTRAINT fk_incident_guardian_response_guardian FOREIGN KEY (guardian_member_id) REFERENCES member (id)
);

-- 구 사건의 첫 기록을 이관한다. 재실행 시 이미 옮긴 행은 그대로 둔다.
INSERT INTO incident_guardian_response
    (incident_id, guardian_member_id, response_type, responded_at_ms, created_at, updated_at)
SELECT incident_id, guardian_response_by, guardian_response_type, guardian_response_at_ms,
       CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
  FROM incident
 WHERE guardian_response_type IS NOT NULL
   AND guardian_response_at_ms IS NOT NULL
   AND guardian_response_by IS NOT NULL
ON DUPLICATE KEY UPDATE incident_guardian_response.id = incident_guardian_response.id;

-- 옛 사건에 due를 백필하지 않는다. 이미 열린 심박 사건의 ②를 배포 시각에 억제한다.
UPDATE incident
   SET second_alert_cancelled_at_ms = CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)
 WHERE kind = 'HR_ANOMALY' AND second_alert_cancelled_at_ms IS NULL;
