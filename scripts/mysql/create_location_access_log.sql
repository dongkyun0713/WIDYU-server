-- #665: 보호자 위치 열람 기록 테이블 (LLD-0056 4절, ADR-0036 결정 3·4, 위치정보법 제16조②·제19조③④).
-- 운영은 ddl-auto: validate라 배포 전에 적용한다.
-- 한 행 = 보호자가 시니어 위치를 한 번 읽은 사실이다.
-- 추가 전용이다. 행을 지우거나 고치지 않고 notified_at만 통보 뒤에 채운다.
-- 좌표를 담지 않는다. 법이 요구하는 것은 「언제 누가 봤는가」다.
-- 자동 삭제는 없다(ADR-0036 결정 6). 보관 상한은 법무 검토 뒤 별도 결정이다.
CREATE TABLE location_access_log (
    location_access_log_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    -- 위치를 본 보호자. 시니어 본인 조회는 행을 만들지 않는다.
    viewer_member_id BIGINT NOT NULL,
    -- 위치가 조회된 시니어.
    senior_member_id BIGINT NOT NULL,
    -- LocationAccessPath enum 이름. REST_LAST / REST_TRAIL / REST_FAMILY /
    -- WS_SUBSCRIBE / HOME_OUTING. MySQL ENUM이 아니라 VARCHAR이므로 경로가 늘어도
    -- ALTER TABLE이 필요하지 않다.
    path VARCHAR(20) NOT NULL,
    -- 서버 시각. 행이 불변이라 updated_at을 두지 않는다.
    accessed_at DATETIME(6) NOT NULL,
    -- 통보 FCM을 넣은 시각. NULL은 아직 알리지 않은 행이고 다음 다이제스트 대상이다.
    notified_at DATETIME(6) NULL,
    CONSTRAINT fk_location_access_log_viewer FOREIGN KEY (viewer_member_id) REFERENCES member (id),
    CONSTRAINT fk_location_access_log_senior FOREIGN KEY (senior_member_id) REFERENCES member (id),
    -- 시니어가 자기 기록을 기간으로 훑을 때 쓴다.
    INDEX idx_location_access_log_senior_time (senior_member_id, accessed_at),
    -- 쿨다운 판단과 다이제스트 대상 조회에 쓴다.
    INDEX idx_location_access_log_senior_notified (senior_member_id, notified_at)
) DEFAULT CHARSET = utf8mb4;
