-- #646: 심박 샘플에 정확도와 배치 원문 참조를 남긴다 (LLD-0047 8절).
-- heart_rate_event는 이미 운영에 배포된 테이블이라 **운영 배포 전 실행이 필수**다.
-- 단건 경로로 들어온 기존 행과 앞으로의 단건 행은 두 값이 NULL이다.
ALTER TABLE heart_rate_event
    ADD COLUMN accuracy VARCHAR(12) NULL,
    ADD COLUMN batch_id VARCHAR(26) NULL;
