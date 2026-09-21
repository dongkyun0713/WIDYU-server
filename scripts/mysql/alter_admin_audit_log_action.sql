-- #658: admin_audit_log.action에 실증 참여 관련 관리자 액션 값을 추가한다 (LLD-0052 8절).
-- 운영 배포 전에 실행한다. 적용하지 않으면 참여 등록·철회·삭제 처리의 감사 로그 INSERT가 실패하고,
-- 감사 로그를 참여 변경과 같은 트랜잭션에 남기므로 참여 변경까지 통째로 롤백된다.
--
-- **먼저 확인한다.** admin_audit_log에는 전용 생성 SQL이 없어 Hibernate가 만든 테이블이다.
-- Hibernate 6은 MySQL에서 @Enumerated(STRING)을 native ENUM으로 매핑하므로 action은 ENUM일 가능성이
-- 높지만, 생성 시점의 버전·설정에 따라 VARCHAR일 수도 있다.
--
--   SHOW COLUMNS FROM admin_audit_log LIKE 'action';
--
-- 결과 Type이 varchar(...)이면 이 스크립트는 **건너뛴다**(값 추가에 DDL이 필요 없다).
-- enum(...)이면 아래를 실행한다.
--
-- 값 목록은 com.widyu.admin.AdminAction의 선언 순서와 같다. 기존 값은 하나도 빼지 않는다 —
-- 목록에서 빠진 값을 가진 기존 행은 빈 문자열이 된다. 위 SHOW COLUMNS 결과에 이 목록에 없는 값이
-- 있으면 그 값도 함께 넣고 실행한다.
ALTER TABLE admin_audit_log
    MODIFY COLUMN action ENUM(
        'ADMIN_LOGIN',
        'MEMBER_STATUS_CHANGE',
        'FCM_TEST_SEND',
        'COLLECTION_RUN_OPEN',
        'COLLECTION_RUN_CLOSE',
        'STUDY_PARTICIPATION_REGISTER',
        'STUDY_PARTICIPATION_PERIOD_CHANGE',
        'STUDY_PARTICIPATION_WITHDRAW',
        'STUDY_PARTICIPATION_DELETION_PROCESSED'
    ) NOT NULL;
