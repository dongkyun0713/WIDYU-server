-- #760, LLD-0085. alter_incident_state_length.sql 뒤에 적용한다.
-- 운영 ddl-auto=validate 기동 전에 확인 화면 수신 열을 추가한다. 기존 사건은 NULL(화면 미수신)로 둔다.
ALTER TABLE incident
    ADD COLUMN prompt_displayed_at_ms BIGINT NULL,
    ADD COLUMN prompt_displayed_via VARCHAR(16) NULL;
