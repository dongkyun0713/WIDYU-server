-- #603 / LLD-0033. 운영 실행은 별도 승인 후 수행한다.
-- 구 애플리케이션을 중지한 뒤 적용하고 새 버전만 기동한다.
ALTER TABLE member ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE member ADD COLUMN reactivation_blocked BOOLEAN NOT NULL DEFAULT FALSE;

-- 기존 enum 정의 전체를 유지한다. DELETED를 탈퇴, INACTIVE를 정지로 사용한다.
ALTER TABLE member MODIFY COLUMN status ENUM('ACTIVE', 'INACTIVE', 'DELETED', 'PROCESSING') NOT NULL;

-- 과거 INACTIVE는 정지와 탈퇴가 혼재한다. 이름/전화번호로 추정하지 않는다.
-- 확인 전에는 관리자 재활성화와 정지 재설정도 차단한다.
UPDATE member SET reactivation_blocked = TRUE WHERE status = 'INACTIVE';
-- 별도 검토로 정지임이 확인된 ID만 reactivation_blocked = FALSE로 변경한다.
-- 탈퇴임이 확인된 ID는 status = 'DELETED'로 변경하고 차단을 유지한다.
SELECT id, status FROM member WHERE reactivation_blocked = TRUE;

-- 검증: NULL/음수 버전이 없어야 한다. 버전 없는 기존 JWT/ws-token은 새 서버가 거절한다.
SELECT COUNT(*) AS invalid_auth_version FROM member WHERE auth_version IS NULL OR auth_version < 0;
-- 롤백 시 auth_version을 삭제/초기화하지 않는다. 구 서버 롤백은 폐기 보장을 잃는다.
