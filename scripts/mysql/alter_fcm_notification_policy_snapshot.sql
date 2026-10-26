-- LLD-0097 / #891: 상세 알림 enum 제거. 애플리케이션과 워커를 중지하고 백업 후 1회 실행한다.
-- MySQL DDL은 자동 커밋된다. 아래 변경은 한 트랜잭션으로 롤백되지 않는다.
-- 먼저 SHOW CREATE TABLE fcm_notification / fcm_outbox로 컬럼·인덱스를 확인한다.
-- 재실행 전 center_filter 컬럼 존재 여부를 확인한다(ADD COLUMN은 1회 전용).
-- 새 앱이 JSON을 쓰기 시작한 뒤 구 enum 앱으로 단순 롤백하면 읽기에 실패한다.
-- 롤백은 쓰기 중지와 사전 백업 복구를 함께 계획한다. 이 스크립트는 데이터를 삭제하지 않는다.

ALTER TABLE fcm_notification MODIFY COLUMN type TEXT NULL;
ALTER TABLE fcm_outbox MODIFY COLUMN notification_type TEXT NULL;
ALTER TABLE fcm_notification ADD COLUMN center_filter VARCHAR(16) NULL;

-- 기존 type 문자열은 호환 변환기가 읽는다. JSON으로 강제 변환할 필요는 없다.
-- 필터는 예전 상세 타입 매핑대로 채운다. 포인트·가족·본인확인·동기화는 NULL 유지.
UPDATE fcm_notification SET center_filter = CASE
    WHEN type IN ('ALBUM_UPLOAD_COMPLETE','ALBUM_CREATED','ALBUM_COMMENTED',
        'ALBUM_REPLIED','ALBUM_LIKED','ALBUM_UNLOCKED','ALBUM_ALL_VIEWED') THEN 'ALBUM'
    WHEN type IN ('MEDICATION_REMINDER_10','MEDICATION_REMINDER_20','MEDICATION_PROOF_MISSING',
        'MEDICATION_SCHEDULE_CHANGED','MEDICATION_SCHEDULE_CREATED','MEDICATION_SCHEDULE_DELETED',
        'HEALTH_SCHEDULE_UPCOMING','HEALTH_SCHEDULE_CREATED','HEALTH_SCHEDULE_UPDATED',
        'HEALTH_SCHEDULE_DELETED','WALK_GOAL_UNMET','WALK_GOAL_CHANGED',
        'GOAL_ACHIEVED','GOALS_ACHIEVED_GROUPED') THEN 'GOAL'
    WHEN type IN ('HEART_MESSAGE_RECEIVED','CHEER_MESSAGE_RECEIVED') THEN 'MESSAGE'
    WHEN type IN ('HEART_RATE_EMERGENCY','SELF_HELP_REQUESTED','SELF_HELP_REQUEST_CANCELLED',
        'SAFETY_SENIOR_OK_NOTICE_HEART','SAFE_ZONE_EXITED','SAFE_ZONE_ENTERED') THEN 'LOCATION'
    ELSE NULL
END
WHERE type IS NOT NULL AND type NOT LIKE '{%';

-- 신앱 배포 전: 아래 결과의 건수·필터를 백업 기준과 대조한다.
SELECT center_filter, COUNT(*) AS row_count FROM fcm_notification GROUP BY center_filter;
-- 신앱 배포 후: 새 행 type/notification_type에 JSON이 저장되고 상세 enum 신규값이 안 생기는지 확인한다.
-- 개인정보·실제 payload를 공유 로그에 출력하지 않는다.
