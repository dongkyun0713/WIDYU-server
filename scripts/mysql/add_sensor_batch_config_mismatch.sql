-- #633: 앱이 적용한 설정이 서버 지시값과 다른 배치를 표시한다(LLD-0046, 지시서 B12).
-- v2 스키마를 이미 만든 dev에 적용한다. 아직 만들지 않았으면 create_sensor_batch.sql에 컬럼이 들어 있다.
-- 배치를 거부하지 않고 표시만 하므로 기존 행은 FALSE로 채운다.
ALTER TABLE sensor_batch
    ADD COLUMN config_mismatch BOOLEAN NOT NULL DEFAULT FALSE;
