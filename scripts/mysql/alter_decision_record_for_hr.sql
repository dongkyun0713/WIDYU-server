-- #661: 심박 판정을 같은 판정 기록 표에 받기 위한 변경 (LLD-0053 4절, ADR-0035 결정 2).
-- 판정 사유와 그때의 심박 값은 이 네 열에만 둔다. 로그·응답 DTO에는 넣지 않는다(정책 1.5.5·1.5.11).
-- 낙상 행은 네 열을 모두 비우므로 전부 NULL을 허용한다.
ALTER TABLE decision_record
    ADD COLUMN hr_bpm INT NULL,
    ADD COLUMN hr_measured_at_ms BIGINT NULL,
    ADD COLUMN hr_accuracy VARCHAR(12) NULL,
    ADD COLUMN reason TEXT NULL;
