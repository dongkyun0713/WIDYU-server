-- #666: 관리자 개인정보 조회·변경 접속기록 테이블 (LLD-0057 4절, ADR-0036 결정 5·6).
-- 운영은 ddl-auto: validate라 배포 전에 적용한다.
-- 추가 전용이다. 고시 제8조①2호에 따라 최소 2년 보관하며 삭제 스케줄러를 두지 않는다.
-- 요청 본문·Authorization 헤더는 담지 않는다.
CREATE TABLE admin_access_log (
    admin_access_log_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    -- 인증 전 요청(관리자 로그인)은 -1로 남긴다.
    admin_id BIGINT NOT NULL,
    admin_name VARCHAR(50) NOT NULL,
    method VARCHAR(8) NOT NULL,
    path VARCHAR(255) NOT NULL,
    -- 쿼리 문자열. 500자를 넘으면 잘라서 저장한다.
    query VARCHAR(500) NULL,
    -- 경로 변수 memberId가 있을 때만 채운다.
    target_member_id BIGINT NULL,
    -- 경로 변수 runId·participationId·exportId 중 하나.
    target_ref VARCHAR(64) NULL,
    status INT NOT NULL,
    client_ip VARCHAR(45) NULL,
    user_agent VARCHAR(200) NULL,
    -- 요청 시작 시각.
    accessed_at DATETIME(6) NOT NULL,
    INDEX idx_admin_access_log_admin_time (admin_id, accessed_at),
    INDEX idx_admin_access_log_time (accessed_at)
);
