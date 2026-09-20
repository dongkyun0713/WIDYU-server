-- #664: 인앱 동의 기록 테이블 (LLD-0055 4절, ADR-0036 결정 1, 정책서 v1.1 B 1.5.10).
-- 운영은 ddl-auto: validate라 배포 전에 적용한다.
-- 한 행 = 한 회원이 한 항목에 대해 한 번 밝힌 의사(동의 또는 철회)다.
-- 추가 전용이다. UPDATE·DELETE를 하지 않으므로 이 테이블이 곧 이력이고
-- 현재 상태는 항목별 최신 행이다. 철회도 granted=0 행을 새로 남긴다.
-- 자동 삭제는 없다(ADR-0036 결정 6). 보관 상한은 법무 검토 뒤 별도 결정이다.
CREATE TABLE consent_record (
    consent_record_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    member_id BIGINT NOT NULL,
    -- ConsentKey enum 이름. PRIVACY_PERSONAL / PRIVACY_HEALTH / LOCATION /
    -- GUARDIAN_LOCATION_PROVIDE / LOCATION_NOTICE_BATCHED / RETENTION_NOTICE
    consent_key VARCHAR(40) NOT NULL,
    -- 앱이 보여 준 동의문의 판. 철회 행은 직전 행의 판을 복사하고, 직전 행이 없으면 '-'다.
    version VARCHAR(32) NOT NULL,
    -- 0은 철회 또는 미동의를 뜻한다.
    granted BOOLEAN NOT NULL,
    -- 서버 시각. 행이 불변이라 updated_at을 두지 않는다.
    recorded_at DATETIME(6) NOT NULL,
    -- APP / ADMIN. 지금은 APP만 쓴다.
    source VARCHAR(16) NOT NULL,
    CONSTRAINT fk_consent_record_member FOREIGN KEY (member_id) REFERENCES member (id),
    INDEX idx_consent_record_member_key_time (member_id, consent_key, recorded_at)
) DEFAULT CHARSET = utf8mb4;
