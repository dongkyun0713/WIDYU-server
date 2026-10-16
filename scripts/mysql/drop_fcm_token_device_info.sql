-- #714: 기기 정보를 수집하지 않는 서버를 모든 인스턴스에 배포한 뒤 수동 실행한다.
-- 이전 서버가 남아 있거나 이전 버전으로 롤백할 수 있으면 실행하지 않는다.
-- JPA ddl-auto=update는 제거된 필드의 컬럼을 자동 삭제하지 않는다.
-- 실행하면 기존 기기 정보가 삭제된다. 운영 DB에는 자동 적용하지 않는다.
ALTER TABLE member_fcm_token DROP COLUMN device_info;
