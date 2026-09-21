-- #662: FcmCategory에 INCIDENT_SELF_CHECK(본인확인 푸시)를 추가한다.
--
-- 적용 전에 운영에서 컬럼 타입을 확인한다:
--   SHOW COLUMNS FROM fcm_notification LIKE 'fcm_category';
--   SHOW COLUMNS FROM fcm_outbox LIKE 'fcm_category';
-- VARCHAR면 값이 그냥 들어가므로 이 파일을 건너뛴다. Hibernate가 만든 네이티브 ENUM이면
-- ddl-auto가 기존 ENUM에 새 값을 넣어 주지 않으므로 아래를 실행한다.
--
-- ※ #665가 같은 컬럼에 LOCATION_NOTICE를 더한다. 두 PR이 모두 머지된 뒤에 적용한다면
--   ENUM 목록에 INCIDENT_SELF_CHECK와 LOCATION_NOTICE를 함께 넣어 한 번에 실행한다.
ALTER TABLE fcm_notification
    MODIFY COLUMN fcm_category ENUM(
        'ALL','ALBUM','TARGET','HEALTH_SCHEDULE','WALK','MEDICINE_SCHEDULE',
        'HEART_MESSAGE','SAFE_ZONE','INCIDENT_SELF_CHECK','LOCATION_NOTICE','ETC') NULL;

ALTER TABLE fcm_outbox
    MODIFY COLUMN fcm_category ENUM(
        'ALL','ALBUM','TARGET','HEALTH_SCHEDULE','WALK','MEDICINE_SCHEDULE',
        'HEART_MESSAGE','SAFE_ZONE','INCIDENT_SELF_CHECK','LOCATION_NOTICE','ETC') NOT NULL;
