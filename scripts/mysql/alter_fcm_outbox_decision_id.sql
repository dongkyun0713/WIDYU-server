-- #661: 위급 알림이 어느 판정에서 나왔는지 outbox와 전송 완료 알림 행이 들고 있게 한다
-- (LLD-0053 4절, ADR-0035 결정 3).
-- outbox는 완료 지점에서 행 자신이 가진 값만 읽어 판정의 alert_delivered를 채우므로 인덱스를 두지 않는다.
-- 판정에서 나오지 않은 알림(대부분)은 NULL이다.
ALTER TABLE fcm_outbox
    ADD COLUMN decision_id VARCHAR(40) NULL;

-- 전송 성공 이력도 같은 연결키를 보존해야 연구 철회 시 해당 판정에서 나온 알림만 선택해 지울 수 있다.
ALTER TABLE fcm_notification
    ADD COLUMN decision_id VARCHAR(40) NULL,
    ADD INDEX idx_fcm_notification_decision (decision_id);
