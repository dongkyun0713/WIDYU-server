-- #643: 측정회차·기기 배정·마커 테이블 (LLD-0045 4절, 작업지시서 B8).
-- 운영은 ddl-auto: validate라 배포 전에 적용한다.
-- 실증은 기기를 여러 참가자가 돌려 쓴다. 기기 번호만으로는 자료가 누구 것인지 알 수 없어
-- 회차가 「누가·어떤 기기를·어디에 차고·언제부터 언제까지」를 묶는다.
CREATE TABLE collection_run (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    -- 서버 발급 `run-` + UUID 하이픈 제거 32자. 사람이 읽는 회차 번호는 protocol_ref에 둔다.
    run_id VARCHAR(40) NOT NULL,
    member_id BIGINT NOT NULL,
    study_id VARCHAR(64) NULL,
    participation_id VARCHAR(64) NULL,
    protocol_ref VARCHAR(64) NULL,
    -- 서면 연구 동의 판(질의 ① 경량 테이블 확정).
    consent_version VARCHAR(50) NULL,
    collection_mode VARCHAR(10) NOT NULL,
    started_at_ms BIGINT NOT NULL,
    ended_at_ms BIGINT NULL,
    status VARCHAR(10) NOT NULL,
    -- 열린 회차일 때만 1. (member_id, open_marker) UK로 한 회원의 동시 열린 회차를 막는다.
    open_marker TINYINT NULL,
    -- 보존 정보(형식서 §6). 실증 기간에는 없어도 회차를 열 수 있다.
    data_policy VARCHAR(20) NULL,
    identified_until DATE NULL,
    pseudonymized_at DATE NULL,
    research_until DATE NULL,
    quality_notes VARCHAR(500) NULL,
    missing_reason VARCHAR(64) NULL,
    -- 연구 회수 모드 회차의 수용 영수증 번호(K3). 아직 채우지 않는다.
    acceptance_receipt_id VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_collection_run_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT uk_collection_run_run_id UNIQUE (run_id),
    CONSTRAINT uk_collection_run_member_open UNIQUE (member_id, open_marker),
    INDEX idx_collection_run_member_status (member_id, status)
);

-- 배정 구간 [assigned_at_ms, unassigned_at_ms)가 자료를 어느 참가자에게 붙일지 정한다.
CREATE TABLE run_device_assignment (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    assignment_id VARCHAR(40) NOT NULL,
    run_id BIGINT NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    role VARCHAR(20) NOT NULL,
    wear_site VARCHAR(30) NULL,
    assigned_at_ms BIGINT NOT NULL,
    unassigned_at_ms BIGINT NULL,
    -- 배정 중일 때만 1. (device_id, active_marker) UK로 기기 이중 배정을 막는다.
    active_marker TINYINT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_run_device_assignment_run FOREIGN KEY (run_id) REFERENCES collection_run (id),
    CONSTRAINT uk_run_device_assignment_id UNIQUE (assignment_id),
    CONSTRAINT uk_run_device_assignment_active UNIQUE (device_id, active_marker),
    INDEX idx_run_device_assignment_device (device_id, unassigned_at_ms)
);

-- 마커는 정답 라벨이다(정책 1.8.1~1.8.3). 판정 결과 기록과 같은 자리에 섞지 않는다.
-- 누른 기기의 시계와 워치의 시계가 다르므로 clock_mapping_id를 함께 남겨 같은 시간축에 놓는다.
CREATE TABLE run_marker (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    marker_id VARCHAR(64) NOT NULL,
    run_id BIGINT NOT NULL,
    kind VARCHAR(30) NOT NULL,
    label VARCHAR(100) NULL,
    source_elapsed_ns BIGINT NOT NULL,
    ts_ms BIGINT NOT NULL,
    source VARCHAR(20) NOT NULL,
    source_device_id VARCHAR(64) NOT NULL,
    clock_mapping_id VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_run_marker_run FOREIGN KEY (run_id) REFERENCES collection_run (id),
    CONSTRAINT uk_run_marker_id UNIQUE (marker_id),
    INDEX idx_run_marker_run_time (run_id, ts_ms)
);
