-- LLD-0095 / #881. 서버 배포 전에 구 앱의 설정 쓰기를 중지하고 DB를 백업한 뒤 실행한다.
-- 선행: LLD-0065 마이그레이션 완료, member_notification_setting.category = VARCHAR(32).
-- 확인: SHOW CREATE TABLE member_notification_setting;
-- 확인: SELECT m.type, s.category, s.enabled, COUNT(*)
--       FROM member_notification_setting s JOIN member m ON m.id = s.member_id
--       GROUP BY m.type, s.category, s.enabled;
-- 아래 조회가 한 행이라도 반환되면 중지한다. 새 시니어 그룹과 구 GENERAL이 공존하는
-- 데이터는 자동으로 덮어쓰지 않는다.
-- SELECT s.member_id, s.category
-- FROM member_notification_setting s JOIN member m ON m.id = s.member_id
-- WHERE m.type = 'SENIOR' AND s.category IN ('ALBUM', 'GOAL', 'HOME', 'ETC');
-- MySQL DDL은 없다. 트랜잭션 실패 시 ROLLBACK 후 백업 및 항목별 건수를 대조한다.

-- H2-DML-BEGIN: 매핑 테스트는 이 구간만 실행하며 운영 MySQL 검증을 대체하지 않는다.
CREATE TEMPORARY TABLE tmp_senior_general_off AS
SELECT s.member_id
FROM member_notification_setting s
JOIN member m ON m.id = s.member_id
WHERE m.type = 'SENIOR' AND s.category = 'GENERAL' AND s.enabled = 0;

START TRANSACTION;
INSERT INTO member_notification_setting (member_id, category, enabled)
SELECT member_id, 'ALBUM', 0 FROM tmp_senior_general_off;

INSERT INTO member_notification_setting (member_id, category, enabled)
SELECT member_id, 'GOAL', 0 FROM tmp_senior_general_off;

INSERT INTO member_notification_setting (member_id, category, enabled)
SELECT member_id, 'HOME', 0 FROM tmp_senior_general_off;

INSERT INTO member_notification_setting (member_id, category, enabled)
SELECT member_id, 'ETC', 0 FROM tmp_senior_general_off;

-- 구 GENERAL=true는 기본 ON과 같으므로 행을 만들지 않는다.
DELETE FROM member_notification_setting
WHERE category = 'GENERAL'
  AND member_id IN (SELECT id FROM member WHERE type = 'SENIOR');
COMMIT;
DROP TEMPORARY TABLE tmp_senior_general_off;
-- H2-DML-END

-- 확인: 시니어 GENERAL 건수 0, 시니어 네 항목 OFF 건수는 구 GENERAL=false 건수와 각각 일치.
-- 확인: 보호자 GENERAL 건수·값 불변, 회원×카테고리 중복 0.
-- SELECT member_id, category, COUNT(*) FROM member_notification_setting
-- GROUP BY member_id, category HAVING COUNT(*) > 1;
-- 이 스크립트는 GENERAL 삭제 후 재실행 시 데이터 변경이 없다. 이미 새 앱이 설정을 쓴
-- 데이터에 재실행하거나 LLD-0065 이전 데이터에 먼저 실행하지 않는다.
