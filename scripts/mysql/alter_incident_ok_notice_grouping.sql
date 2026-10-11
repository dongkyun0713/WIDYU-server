-- #708, LLD-0072. alter_incident_self_check_first.sql 뒤에 적용한다.
-- 운영 ddl-auto=validate 기동 전에 새 열과 마지막 감지 시각 백필을 완료한다.
ALTER TABLE incident
    ADD COLUMN ok_notice_sent_at_ms BIGINT NULL,
    ADD COLUMN last_detected_at_ms BIGINT NULL;

UPDATE incident
   SET last_detected_at_ms = opened_at_ms
 WHERE last_detected_at_ms IS NULL;
