-- #658: 실증 참여 기록 4테이블과 연구 회차의 참여 기록 FK (LLD-0052 4절·8절).
-- 운영은 ddl-auto: validate라 애플리케이션 배포 전에 적용한다.
--
-- 한 사람의 한 번의 실증 참여가 연구 보관 정책의 정본이다. 연구 회차는 이 행을 참조하고,
-- 회차에 중복으로 들어 있던 연구 메타데이터는 새 회차에서 더 이상 쓰지 않는다.
-- 중복 컬럼 DROP은 운영 데이터 백필과 함께 별도 승인 후 실행한다(이 스크립트에 넣지 않는다).
--
-- #617의 study_participation이 남아 있으면(운영 미배포·데이터 없음) 먼저 DROP 후 실행한다.
--
-- status·withdrawal_scope·history_type은 VARCHAR다. 다른 연구 테이블(collection_run 등)과 같은 방식이라
-- 값이 늘어도 ENUM ALTER가 필요 없다. 엔티티도 @JdbcTypeCode(SqlTypes.VARCHAR)로 고정해
-- Hibernate가 MySQL에서 native ENUM으로 매핑하지 않게 했다(그러지 않으면 ddl-auto: validate가 어긋난다).
CREATE TABLE study_participation (
    study_participation_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    -- 서버 발급 `part-` + UUID 하이픈 제거 32자. 클라이언트가 정하지 않는다.
    participation_id VARCHAR(50) NOT NULL,
    study_id VARCHAR(50) NOT NULL,
    -- 재식별 키. 참여 기록 자체에는 이름·전화번호를 두지 않는다.
    member_id BIGINT NOT NULL,
    -- 서면 연구동의 판과 서명일.
    consent_version VARCHAR(50) NOT NULL,
    consented_at DATE NOT NULL,
    -- 보관 계획. IRB 승인 전에는 넷 모두 비어 있을 수 있고, 날짜는 셋 전부이거나 전무다.
    data_policy VARCHAR(50) NULL,
    identified_until DATE NULL,
    pseudonymized_at DATE NULL,
    research_until DATE NULL,
    -- ACTIVE / ENDED / WITHDRAWN
    status VARCHAR(20) NOT NULL,
    withdrawn_at DATETIME(6) NULL,
    -- ALL / SELECTED_CONSENTS
    withdrawal_scope VARCHAR(24) NULL,
    -- 철회 후 운영자가 수동 삭제를 마친 시각. 삭제 실행은 서버가 하지 않는다.
    deletion_processed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    -- 같은 연구·회원의 ACTIVE 참여를 DB에서 하나로 묶는다. ACTIVE일 때만 '1'이고 그 외에는 NULL이라
    -- UNIQUE 대상에서 빠지므로 철회·종료한 참여는 얼마든지 쌓인다.
    -- 값은 엔티티가 상태와 함께 채운다(collection_run.open_marker와 같은 방식).
    -- MySQL 생성 컬럼을 쓰지 않는 이유: ddl-auto로 스키마를 만드는 dev·테스트에는 생성 컬럼이 없어
    -- 그 환경에서만 ACTIVE 중복이 새는 것을 막기 위해서다.
    active_key CHAR(1) NULL,
    CONSTRAINT fk_study_participation_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT uk_study_participation_id UNIQUE (participation_id),
    CONSTRAINT uk_study_participation_active UNIQUE (study_id, member_id, active_key),
    INDEX idx_study_participation_active (study_id, member_id, status)
);

-- 선택 동의 항목별 동의 여부. 직접식별자를 두지 않는다.
CREATE TABLE study_participation_consent (
    study_participation_id BIGINT NOT NULL,
    consent_key VARCHAR(64) NOT NULL,
    granted BIT(1) NOT NULL,
    PRIMARY KEY (study_participation_id, consent_key),
    CONSTRAINT fk_study_participation_consent_participation
        FOREIGN KEY (study_participation_id) REFERENCES study_participation (study_participation_id)
);

-- 일부 철회의 대상 항목. 전체 철회면 행이 없다.
CREATE TABLE study_participation_withdrawal_item (
    study_participation_id BIGINT NOT NULL,
    consent_key VARCHAR(64) NOT NULL,
    PRIMARY KEY (study_participation_id, consent_key),
    CONSTRAINT fk_study_participation_withdrawal_participation
        FOREIGN KEY (study_participation_id) REFERENCES study_participation (study_participation_id)
);

-- 현행 행을 덮어써도 과거 보관 계획과 철회 범위가 남도록 변경 시점마다 snapshot을 쌓는다.
CREATE TABLE study_participation_history (
    study_participation_history_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    study_participation_id BIGINT NOT NULL,
    -- REGISTERED / RETENTION_CHANGED / WITHDRAWN / DELETION_PROCESSED
    history_type VARCHAR(24) NOT NULL,
    data_policy VARCHAR(50) NULL,
    identified_until DATE NULL,
    pseudonymized_at DATE NULL,
    research_until DATE NULL,
    status VARCHAR(20) NOT NULL,
    withdrawn_at DATETIME(6) NULL,
    withdrawal_scope VARCHAR(24) NULL,
    deletion_processed_at DATETIME(6) NULL,
    -- 일부 철회로 거둔 항목의 JSON 배열. 현재 행의 철회 항목을 덮어써도 그때 값이 남는다.
    withdrawn_consent_keys TEXT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_study_participation_history_participation
        FOREIGN KEY (study_participation_id) REFERENCES study_participation (study_participation_id),
    INDEX idx_study_participation_history_participation (study_participation_id, created_at)
);

-- 새 AdminAction 값은 admin_audit_log.action에 따로 반영한다. 그 테이블은 Hibernate가 만들어
-- MySQL에서 ENUM일 수 있다 → scripts/mysql/alter_admin_audit_log_action.sql을 함께 실행한다.

-- 연구 회차가 참조하는 참여 기록. product 회차와 이 기능 이전에 열린 회차는 NULL이다.
ALTER TABLE collection_run
    ADD COLUMN study_participation_id BIGINT NULL,
    ADD CONSTRAINT fk_collection_run_study_participation
        FOREIGN KEY (study_participation_id) REFERENCES study_participation (study_participation_id);
