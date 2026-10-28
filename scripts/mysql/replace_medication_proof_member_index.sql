-- 복약 인증의 회원별 날짜 조회 인덱스를 인증 시각(verified_at)에서 복용 기준일(verified_date)로 바꾼다 (#872)
-- 조회가 verified_date로 거르므로 (member_id, verified_at) 인덱스로는 날짜 범위를 좁히지 못한다(LLD-0024 인수조건).
--
-- 운영은 ddl-auto: validate라 인덱스를 만들지 않는다. 이 변경을 배포하기 전에 1회 실행한다.
-- 새 인덱스가 member_id 외래 키를 받치므로 기존 인덱스를 지울 수 있다. 순서를 바꾸지 않는다.
--
-- 실행 방법:
--   docker compose exec mysql mysql -u<user> -p<password> widyu < scripts/mysql/replace_medication_proof_member_index.sql

CREATE INDEX idx_medication_proof_member_verified_date ON medication_proof (member_id, verified_date);

DROP INDEX idx_medication_proof_member_verified ON medication_proof;
