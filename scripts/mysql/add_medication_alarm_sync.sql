-- #619: 복약 알람 스냅샷 동기화 기능을 운영에 배포하기 전에 1회 실행합니다.
-- 운영 프로필은 ddl-auto: validate이므로 이 스크립트 적용 전에는 애플리케이션이 기동하지 않습니다.

ALTER TABLE member
    ADD COLUMN medication_alarm_revision BIGINT NOT NULL DEFAULT 0;

ALTER TABLE fcm_outbox
    ADD COLUMN data_type VARCHAR(100) NULL,
    ADD COLUMN data_revision BIGINT NULL;
