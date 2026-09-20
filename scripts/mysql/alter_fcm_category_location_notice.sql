-- #665: FcmCategory에 LOCATION_NOTICE를 추가하면서 필요한 컬럼 확장 (LLD-0056 8절).
--
-- ▶ 먼저 운영에서 컬럼 타입을 확인한다. ENUM일 때만 아래를 실행하고, VARCHAR면 건너뛴다.
--
--     SHOW COLUMNS FROM fcm_notification LIKE 'fcm_category';
--     SHOW COLUMNS FROM member_notification_setting LIKE 'category';
--
--   두 테이블에는 손으로 쓴 생성 SQL이 없고 Hibernate가 만들었다. Hibernate 6은 MySQL에서
--   @Enumerated(STRING)을 native ENUM으로 매핑하므로 ENUM일 가능성이 높고, 그러면 새 값을
--   쓰는 순간 INSERT가 실패한다. 어느 쪽이든 VARCHAR면 ALTER 없이 그대로 동작한다.
--
--   fcm_outbox는 scripts/mysql/create_fcm_outbox.sql이 fcm_category를 VARCHAR(32)로
--   만들어 두었으므로 대상이 아니다.
--
-- 값 순서는 FcmCategory 선언 순서와 같게 유지한다. ENUM은 순서가 저장값의 의미를 바꾸지 않지만
-- 읽는 사람이 enum과 대조하기 쉬워야 한다.

ALTER TABLE fcm_notification
    MODIFY COLUMN fcm_category ENUM(
        'ALL', 'ALBUM', 'TARGET', 'HEALTH_SCHEDULE', 'WALK', 'MEDICINE_SCHEDULE',
        'HEART_MESSAGE', 'SAFE_ZONE', 'LOCATION_NOTICE', 'ETC');

ALTER TABLE member_notification_setting
    MODIFY COLUMN category ENUM(
        'ALL', 'ALBUM', 'TARGET', 'HEALTH_SCHEDULE', 'WALK', 'MEDICINE_SCHEDULE',
        'HEART_MESSAGE', 'SAFE_ZONE', 'LOCATION_NOTICE', 'ETC') NOT NULL;
