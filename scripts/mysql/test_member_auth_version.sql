-- 저장소 루트에서 로컬 테스트 DB에만 실행한다.
-- TEMPORARY TABLE이 원래 member를 가리므로 실제 회원 테이블은 변경하지 않는다.
CREATE TEMPORARY TABLE member (
    id BIGINT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    phone_number VARCHAR(255),
    status ENUM('ACTIVE', 'INACTIVE', 'DELETED', 'PROCESSING') NOT NULL
);
INSERT INTO member VALUES
    (1, '탈퇴회원', NULL, 'INACTIVE'),
    (2, '확인전회원', '01000000000', 'INACTIVE'),
    (3, '활성회원', '01000000001', 'ACTIVE');

SOURCE scripts/mysql/add_member_auth_version.sql;

-- 0행이 정답이다. 마스킹된 이름도 탈퇴로 추정하지 않고 미분류로 막는다.
SELECT id AS migration_failure FROM member
WHERE auth_version <> 0
   OR (id IN (1, 2) AND (status <> 'INACTIVE' OR reactivation_blocked <> TRUE))
   OR (id = 3 AND (status <> 'ACTIVE' OR reactivation_blocked <> FALSE));
DROP TEMPORARY TABLE member;
