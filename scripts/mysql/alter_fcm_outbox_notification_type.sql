-- #692 / LLD-0060: 새 앱 배포 전에 적용한다. 기존 행은 두 컬럼 모두 NULL이다.
-- 구버전 롤백 전, 그 버전에 없는 notification_type의 PENDING/CLAIMED 행을 CANCELLED로 갱신한다(LLD-0060 8절).
-- fcm_category는 운영 native ENUM일 수 있으므로 이 마이그레이션에서 변경하지 않는다.
ALTER TABLE fcm_outbox
    ADD COLUMN notification_type VARCHAR(48) NULL,
    ADD COLUMN data_payload TEXT NULL;
