-- LLD-0065 / #700. 구 앱의 설정 읽기·쓰기 트래픽을 중지하고 백업한 뒤 실행한다.
-- 확인: SHOW CREATE TABLE member_notification_setting;
-- 확인: SELECT category, COUNT(*) FROM member_notification_setting GROUP BY category;
-- ALBUM, TARGET, HEALTH_SCHEDULE, WALK, ETC, MEDICINE_SCHEDULE, SAFE_ZONE 이외의
-- 값이 있으면 실행을 중단하고 값을 조사한다. 새 그룹 행이 이미 쓰인 운영 DB에는 재실행하지 않는다.
-- 운영 컬럼이 native ENUM일 수 있다. DDL은 암묵적 COMMIT이므로 선행 백업으로 복구한다.

ALTER TABLE member_notification_setting
    MODIFY COLUMN category VARCHAR(32) NOT NULL;

-- 빠진 행은 켜짐이므로 다섯 카테고리가 전부 꺼진 회원만 GENERAL=false로 옮긴다.
-- H2-DML-BEGIN: 단위 테스트는 이 구간의 데이터 매핑만 실행한다. native ENUM DDL 검증이 아니다.
CREATE TEMPORARY TABLE tmp_notification_setting_general AS
SELECT member_id
FROM member_notification_setting
WHERE category IN ('ALBUM', 'TARGET', 'HEALTH_SCHEDULE', 'WALK', 'ETC')
GROUP BY member_id
HAVING COUNT(DISTINCT category) = 5 AND MAX(enabled) = 0;

START TRANSACTION;
DELETE FROM member_notification_setting
WHERE category IN ('ALBUM', 'TARGET', 'HEALTH_SCHEDULE', 'WALK', 'ETC');

INSERT INTO member_notification_setting (member_id, category, enabled)
SELECT member_id, 'GENERAL', false
FROM tmp_notification_setting_general;

UPDATE member_notification_setting
SET category = 'MEDICATION_CHECK'
WHERE category = 'MEDICINE_SCHEDULE';

-- 필수 그룹은 기본 켜짐이며, 신규 비방장 OFF만 앱 배포 뒤 별도 행으로 저장한다.
DELETE FROM member_notification_setting WHERE category = 'SAFE_ZONE';
-- H2-DML-END
COMMIT;

ALTER TABLE member
    ADD COLUMN notification_policy_revision BIGINT NOT NULL DEFAULT 0;

DROP TEMPORARY TABLE tmp_notification_setting_general;

-- 확인: SELECT category, COUNT(*) FROM member_notification_setting GROUP BY category;
-- 확인: SELECT member_id, category, COUNT(*) FROM member_notification_setting
--       GROUP BY member_id, category HAVING COUNT(*) > 1;
-- 중간 실패: 앱 배포를 멈추고 설정 트래픽을 재개하지 않는다. DDL은 롤백되지 않으므로 백업본과 현재 단계의
-- 카테고리별 건수를 대조한 뒤 복구한다. 배포 뒤 재실행은 비방장 SAFE_ZONE OFF를 지운다.
