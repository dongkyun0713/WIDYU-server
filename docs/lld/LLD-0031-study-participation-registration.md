# LLD-0031: 국내 실증(IRB) 연구 참여 등록

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Superseded by LLD-0052 |
| Issue | #617 |
| 관련 ADR | ADR-0026 |
| 작성자 | Claude |
| 작성일 | 2026-09-16 |

> **2026-09-21 폐기.** 이 설계는 [LLD-0052](LLD-0052-study-participation-record.md)가 대체한다. 참여 식별자를 서버가 발급하고, 보관 날짜는 IRB 승인 전까지 비워 둘 수 있으며, 변경 이력은 감사 로그가 아니라 `study_participation_history` snapshot으로 남긴다. 아래 내용은 이력용이다.

## 1. 목적 / 배경

데이터 정책서 v0 1.5.1·1.5.3은 국내 실증(IRB)의 보존 정책을 서버에 등록된 `study_id + participation_id`가 정하도록 확정했다. 필수 필드(보존 날짜 셋, 동의 버전 등)가 하나라도 없으면 수집 시작을 거부하고, 기간 변경은 IRB 승인·동의 범위와 함께 이력을 남겨야 한다. 현재 서버에는 이 모델과 API가 없다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api / widyu-domain
- `StudyParticipation` 엔티티, `DataPolicy`·`StudyParticipationStatus` enum
- Admin 전용 등록·조회·보존 기간 변경 API
- 필수 필드 누락·기간 역전·중복 등록 거부
- 기간 변경 이력을 `AdminAuditLog`에 기록

### Out of scope
- 심박 이벤트의 연구 귀속과 30일 삭제 제외 (LLD-0032)
- 가명화·파기 스케줄러 (LLD-0033)
- 일본 현장실증 정책(`JP_PILOT`) 분기
- 스트림 메타데이터(`device_id`·`session_id`·`seq`)와 판정 기록 (정책서 §3 합의 대기)
- 재식별 키의 DB 권한 분리 (운영 과제)

## 3. 인터페이스 / API

`/api/v1/admin/**`는 `SecurityConfig`에서 `ROLE_ADMIN`만 허용한다. Admin 컨트롤러 관례에 따라 Swagger Docs 인터페이스는 두지 않는다.

```http
POST  /api/v1/admin/studies/participations
GET   /api/v1/admin/studies/participations/{participationId}
PATCH /api/v1/admin/studies/participations/{participationId}/retention
```

등록 요청 (전 필드 필수):

```json
{
  "studyId": "STUDY-2026",
  "participationId": "P-001",
  "memberId": 1023,
  "dataPolicy": "KR_IRB",
  "identifiedUntil": "2027-03-31",
  "pseudonymizedAt": "2027-03-31",
  "researchUntil": "2029-03-31",
  "consentVersion": "v1"
}
```

보존 기간 변경 요청 (전 필드 필수):

```json
{
  "identifiedUntil": "2027-06-30",
  "pseudonymizedAt": "2027-06-30",
  "researchUntil": "2030-03-31",
  "consentVersion": "v2",
  "irbApprovalRef": "IRB-2026-01-A1"
}
```

응답 (세 API 공통):

```json
{
  "code": "200",
  "message": "연구 참여 등록 성공",
  "data": {
    "id": 1,
    "studyId": "STUDY-2026",
    "participationId": "P-001",
    "memberId": 1023,
    "dataPolicy": "KR_IRB",
    "identifiedUntil": "2027-03-31",
    "pseudonymizedAt": "2027-03-31",
    "researchUntil": "2029-03-31",
    "consentVersion": "v1",
    "status": "ACTIVE"
  }
}
```

날짜는 ISO-8601 `LocalDate`다. 정책서 1.1.2의 시간대 offset은 시각 필드에만 해당하며 날짜 셋은 일 단위 정책값이다.

## 4. 데이터 모델

신규 테이블 `study_participation` (엔티티 `com.widyu.study.StudyParticipation`, widyu-domain):

| 컬럼 | 타입 | 제약 |
| --- | --- | --- |
| `study_participation_id` | BIGINT PK | IDENTITY |
| `study_id` | VARCHAR(50) | NOT NULL |
| `participation_id` | VARCHAR(50) | NOT NULL, UK `uk_study_participation_id` |
| `member_id` | BIGINT FK → member | NULL (파기 후 null, ADR-0026) |
| `data_policy` | ENUM(`KR_IRB`) | NOT NULL |
| `identified_until` | DATE | NOT NULL |
| `pseudonymized_at` | DATE | NOT NULL |
| `research_until` | DATE | NOT NULL |
| `consent_version` | VARCHAR(50) | NOT NULL |
| `status` | ENUM(`ACTIVE`,`PSEUDONYMIZED`,`DESTROYED`) | NOT NULL, 등록 시 `ACTIVE` |
| `created_at`, `updated_at` | | `BaseTimeEntity` |

서비스·엔티티 검증: `identified_until <= pseudonymized_at <= research_until`, 회원당 `ACTIVE` 참여 1개.

`AdminAction`에 `STUDY_PARTICIPATION_PERIOD_CHANGE` 추가. `ErrorCode`에 `STUDY_*` 4개 추가.

DTO (widyu-api `study/dto`): `StudyParticipationCreateRequest`, `StudyRetentionChangeRequest`, `StudyParticipationResponse.from()`.

## 5. 처리 흐름

등록:
1. `@Valid`로 필수 필드 검증. 누락 시 `MethodArgumentNotValidException` → 400.
2. `participationId` 중복 → `STUDY_PARTICIPATION_DUPLICATED`.
3. 회원의 `ACTIVE` 참여 존재 → `STUDY_PARTICIPATION_DUPLICATED`.
4. 회원 조회 → 없으면 `MEMBER_NOT_FOUND`.
5. `StudyParticipation.of()`에서 기간 순서 검증 → 저장. 트랜잭션 1개.

보존 기간 변경:
1. `participationId`로 조회 → 없으면 `STUDY_PARTICIPATION_NOT_FOUND`.
2. 변경 전 요약 문자열을 만든다.
3. `changeRetention()`: `ACTIVE`가 아니면 `STUDY_PARTICIPATION_NOT_ACTIVE`, 순서 위반이면 `STUDY_RETENTION_PERIOD_INVALID`.
4. `AdminAuditLogService.log(STUDY_PARTICIPATION_PERIOD_CHANGE, "STUDY_PARTICIPATION", id, detail)`. `detail`은 `irb=<ref>; before[...]; after[...]` 형식(500자 이내). `REQUIRES_NEW`라 본 트랜잭션과 독립이다. 검증 예외는 로그 호출 전에 발생하므로 실패한 변경은 이력에 남지 않는다.

Facade·이벤트 없음.

## 6. 예외 / 에러 처리

| 코드 | HTTP | 조건 |
| --- | --- | --- |
| `STUDY_4041` `STUDY_PARTICIPATION_NOT_FOUND` | 404 | `participationId` 없음 |
| `STUDY_4090` `STUDY_PARTICIPATION_DUPLICATED` | 409 | 같은 `participationId` 또는 회원의 `ACTIVE` 참여 존재 |
| `STUDY_4000` `STUDY_RETENTION_PERIOD_INVALID` | 400 | `identified_until > pseudonymized_at` 또는 `pseudonymized_at > research_until` |
| `STUDY_4001` `STUDY_PARTICIPATION_NOT_ACTIVE` | 400 | `ACTIVE`가 아닌 참여의 기간 변경 |
| `MEMBER_4041` | 404 | 회원 없음 |
| 기존 validation 400 | 400 | 필수 필드 누락 |

## 7. 인수조건 (Acceptance Criteria)

- [x] 필수 필드를 모두 갖춘 등록 요청은 `ACTIVE` 상태로 저장되고 응답에 그대로 반환된다.
- [x] 같은 `participationId` 재등록은 `STUDY_PARTICIPATION_DUPLICATED`로 거부된다.
- [x] 회원에게 `ACTIVE` 참여가 있으면 새 등록은 거부된다.
- [x] `pseudonymized_at < identified_until` 또는 `research_until < pseudonymized_at`이면 등록·변경 모두 `STUDY_RETENTION_PERIOD_INVALID`로 거부된다.
- [x] 보존 기간 변경 성공 시 감사 로그 `detail`에 IRB 승인 참조, 변경 전후 날짜, 변경 전후 동의 버전이 모두 포함된다.
- [x] 변경이 검증에 실패하면 감사 로그를 남기지 않는다.
- [x] 없는 `participationId` 조회는 `STUDY_PARTICIPATION_NOT_FOUND`다.
- [x] `./gradlew compileJava`로 `QStudyParticipation`이 생성된다.
- [x] `bash scripts/harness/run-module-tests.sh`가 통과한다.

## 8. 영향 범위 / 마이그레이션

신규 테이블이라 기존 코드·데이터 영향 없음. 로컬·dev는 `ddl-auto: update`가 생성한다. 운영 DDL:

```sql
CREATE TABLE study_participation (
  study_participation_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  study_id VARCHAR(50) NOT NULL,
  participation_id VARCHAR(50) NOT NULL,
  member_id BIGINT NULL,
  data_policy ENUM('KR_IRB') NOT NULL,
  identified_until DATE NOT NULL,
  pseudonymized_at DATE NOT NULL,
  research_until DATE NOT NULL,
  consent_version VARCHAR(50) NOT NULL,
  status ENUM('ACTIVE','PSEUDONYMIZED','DESTROYED') NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  CONSTRAINT uk_study_participation_id UNIQUE (participation_id),
  CONSTRAINT fk_study_participation_member FOREIGN KEY (member_id) REFERENCES member(member_id)
);
```

`admin_audit_log.action`은 Hibernate 6이 MySQL 네이티브 ENUM으로 생성하며 `ddl-auto: update`는 새 값을 추가하지 않는다. 배포 전 수동 실행한다:

```sql
ALTER TABLE admin_audit_log MODIFY COLUMN action
  ENUM('ADMIN_LOGIN','MEMBER_STATUS_CHANGE','FCM_TEST_SEND','STUDY_PARTICIPATION_PERIOD_CHANGE') NOT NULL;
```

실행 전 `SHOW COLUMNS FROM admin_audit_log LIKE 'action';`으로 현재 타입을 확인한다. VARCHAR이면 ALTER는 필요 없다.

## 9. 미결정 사항 (Open Questions)

- 없음. 후속 범위(LLD-0032·0033, `JP_PILOT`, 판정 기록)는 ADR-0026 후속 절에 기록했다.

## 10. 참고

- `apiDocs/위듀_데이터정책서_v0.md` §1.5
- ADR-0026
- ADR-0025 (재식별 키 암호화 범위는 이 ADR의 보류 결정을 따른다)
