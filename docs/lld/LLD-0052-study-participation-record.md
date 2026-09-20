# LLD-0052: 실증 참여 기록과 연구 회차 수집 게이트

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #658 |
| 관련 ADR | ADR-0026 (본 LLD 범위와 충돌하는 자동 삭제 부분은 적용하지 않음) |
| 작성일 | 2026-09-20 |

## 1. 목적 / 배경

국내 실증 자료는 일반 서비스 자료와 달리 서면 연구동의·선택동의·철회 상태·보관 계획을 추적해야 한다. 현재 `collection_run`에 참여 식별자·동의 판·보관 날짜가 요청값으로 중복 저장되어 있어, 어느 값이 정본인지 불명확하고 유효한 참여 기록 없이 연구 회차를 열 수 있다.

이 기능은 한 사람의 실증 참여 한 번을 `study_participation`으로 기록한다. 참여 기록은 실증 참여 여부와 연구 보관 정책의 정본이며, 연구 회차는 그 기록을 참조한다. 보관 날짜가 아직 확정되지 않은 실증은 등록·수집을 허용한다. 자동삭제·가명화·물리 삭제는 이 범위에 포함하지 않는다.

## 2. 범위

### In scope

- 변경 모듈: `widyu-domain`, `widyu-api`
- 관리자 전용 참여 등록·조회·보관 계획 수정·철회 API
- 서버 발급 불변 `participation_id`, 서면 동의 판·서명일, 선택 동의 항목, 상태와 철회 처리 정보
- 참여 기록 변경마다 불변 snapshot을 남기는 `study_participation_history`
- `collection_run`이 연구 정본인 참여 기록을 FK로 참조하도록 변경
- `collection_mode=research` 회차는 대상 회원·참여 기록·상태가 일치할 때만 개설
- ERD·운영 MySQL DDL·단위/통합 테스트

### Out of scope

- 철회에 따른 센서 S3 원문·심박·위치·인시던트의 자동/수동 삭제 실행
- 보관 날짜 도래에 따른 자동삭제·가명화 스케줄러
- 앱의 일반 서비스 동의(개인정보·위치·보호자 제공) 변경
- 참여자 전용 애플리케이션 역할/권한 생성

## 3. 인터페이스 / API

모든 경로는 기존 `/api/v1/admin/**` 권한 경계를 사용한다. 이름·전화번호는 요청·응답·로그에 포함하지 않는다.

```http
POST  /api/v1/admin/studies/participations
GET   /api/v1/admin/studies/participations/{participationId}
PATCH /api/v1/admin/studies/participations/{participationId}/retention
POST  /api/v1/admin/studies/participations/{participationId}/withdrawal
POST  /api/v1/admin/studies/participations/{participationId}/deletion-processed
```

등록 요청에서 `participationId`는 받지 않는다. 서버가 `part-` + UUID(하이픈 제거)를 발급한다.

```json
{
  "studyId": "STUDY-2026",
  "memberId": 1023,
  "consentVersion": "IRB-v1",
  "consentedAt": "2026-09-20",
  "consents": {"guardian_location_access": true, "ecg": false},
  "dataPolicy": null,
  "identifiedUntil": null,
  "pseudonymizedAt": null,
  "researchUntil": null
}
```

보관 계획 수정은 날짜 셋과 `dataPolicy`를 모두 선택값으로 받는다. 날짜를 하나라도 입력하면 세 날짜를 모두 입력해야 하며 `identifiedUntil <= pseudonymizedAt <= researchUntil`이어야 한다. 보관 날짜와 정책은 아직 IRB 승인이 없으면 모두 `null`로 둘 수 있다.

철회 요청은 `scope=ALL` 또는 `SELECTED_CONSENTS`다. 후자는 비어 있지 않은 `consentKeys`가 필요하다. 철회 성공 시 상태는 `WITHDRAWN`, `withdrawnAt`은 서버 시각, `deletionProcessedAt`은 관리자가 후속 수동 삭제를 마친 때에만 별도 수정 API로 채운다. 이 LLD에서는 철회 요청으로 삭제를 수행하지 않는다.

연구 회차 열기 요청은 `participationId`만 받아 연구 식별자·동의 판·보관 값은 서버가 참여기록에서 결정한다. `collectionMode=product`에는 참여 식별자가 필요 없고, `research`에는 필수다.

## 4. 데이터 모델

### `study_participation`

| 컬럼 | 제약 | 설명 |
| --- | --- | --- |
| `study_participation_id` | PK | 내부 FK |
| `participation_id` | UK, NOT NULL | 서버 발급 외부 식별자 |
| `study_id`, `member_id` | NOT NULL | 연구·회원 연결 |
| `consent_version`, `consented_at` | NOT NULL | 서면 연구동의 판·서명일 |
| `data_policy`, 날짜 3개 | NULL 허용 | IRB 확정 전 빈 값 허용 |
| `status` | NOT NULL | `ACTIVE`, `ENDED`, `WITHDRAWN` |
| `withdrawn_at`, `withdrawal_scope`, `deletion_processed_at` | NULL 허용 | 철회 및 수동 처리 추적 |

선택 동의는 `study_participation_consent(study_participation_id FK, consent_key, granted)`로 저장한다. 일부 철회의 대상 항목은 `study_participation_withdrawal_item(study_participation_id FK, consent_key)`로 저장한다. 두 테이블은 직접식별자를 갖지 않으며, FK는 외부 식별자 `participation_id`가 아니라 내부 PK `study_participation_id`를 가리킨다.

`study_participation_history`는 등록·보관계획변경·철회·처리완료 시점마다 참여기록의 정책/상태 snapshot과 변경 종류·시각을 저장한다. 현행 행을 덮어써도 과거 보관 계획과 철회 범위가 사라지지 않게 한다.

`collection_run`에는 nullable `study_participation_id` FK를 둔다. 기존 `study_id`, `participation_id`, `consent_version`, 보관 날짜 컬럼은 연구 회차에서 쓰지 않으며 운영 마이그레이션으로 제거한다. 회차 응답·내보내기·자료 귀속의 연구 메타데이터는 FK를 따라 읽는다. 읽는 쪽마다 조건 분기를 두지 않도록 `CollectionRun`의 해당 getter가 참여 기록을 우선 읽고, 참여 기록이 없는 회차(`product` 회차, 이 기능 이전 회차)만 남은 컬럼을 읽는다. 참여 기록은 읽는 쪽의 트랜잭션 경계가 제각각이라 즉시 로딩한다.

## 5. 처리 흐름

### 등록

1. 관리자 요청의 회원 존재를 확인한다.
2. 서버가 `participation_id`를 발급하고 참여 기록을 `ACTIVE`로 저장한다.
3. 선택 동의와 `REGISTERED` snapshot을 같은 트랜잭션에서 저장한다.

동일 회원은 여러 연구 또는 시간상 여러 참여 기록을 가질 수 있다. 단, 같은 `study_id + member_id`의 `ACTIVE` 기록은 하나만 허용한다.

### 연구 회차 개설 게이트

1. `collectionMode`가 `research`이면 `participationId`가 없을 때 거절한다.
2. 해당 참여 기록을 조회하고 대상 회원과 일치하며 상태가 `ACTIVE`인지 확인한다.
3. 새 `CollectionRun`은 참여기록 FK만 저장한다. 연구 ID·동의 판·보관 값은 요청값을 신뢰하지 않고 참여기록에서 조회한다.
4. `product` 회차는 참여 기록 없이 기존처럼 개설한다.

### 철회

1. `ACTIVE` 또는 `ENDED` 참여 기록만 철회할 수 있다.
2. 범위와 항목을 검증하고 상태·철회시각·범위를 갱신한다.
3. `WITHDRAWN` snapshot을 남긴다. 실제 데이터 삭제는 이 트랜잭션에서 하지 않는다.

### 관리자 변경 기록

무엇이 어떻게 바뀌었는지는 snapshot이 답하고, 누가·언제 바꿨는지는 기존 관리자 감사 로그(`AdminAuditLogService`)에 남긴다. 등록·보관 계획 수정·철회·삭제 처리 완료가 대상이며, 감사 로그 detail에는 참여·회원·연구 식별자만 담는다. 동의 항목·보관 날짜·이름은 담지 않는다.

## 6. 예외 / 에러 처리

| 코드 | HTTP | 조건 |
| --- | --- | --- |
| `STUDY_PARTICIPATION_NOT_FOUND` | 404 | 식별자가 없음 |
| `STUDY_PARTICIPATION_DUPLICATED` | 409 | 같은 연구·회원의 ACTIVE 기록 |
| `STUDY_RETENTION_PERIOD_INVALID` | 400 | 날짜 일부 누락 또는 순서 역전 |
| `STUDY_PARTICIPATION_NOT_ACTIVE` | 400 | 연구 회차 개설에 ACTIVE가 아닌 기록 사용 |
| `RUN_RESEARCH_PARTICIPATION_REQUIRED` | 400 | 연구 회차에 참여 식별자 없음 |
| `RUN_RESEARCH_PARTICIPATION_MISMATCH` | 400 | 대상 회원과 참여 기록이 다름 |

## 7. 인수조건 (Acceptance Criteria)

- [x] 등록 시 서버 발급 `participation_id`, 연구 동의 판·서명일, 선택 동의가 저장·조회된다.
- [x] 보관 날짜·`dataPolicy`가 모두 비어 있는 참여 기록을 등록할 수 있다.
- [x] 날짜를 일부만 주거나 순서가 역전되면 거절한다.
- [x] 같은 연구·회원에게 ACTIVE 참여 기록을 두 번 만들 수 없다.
- [x] 보관 계획 수정·철회·처리 완료는 각각 불변 변경 이력을 남긴다.
- [x] 연구 회차는 ACTIVE 참여 기록 없이, 또는 다른 회원의 참여 기록으로 열 수 없다.
- [x] 연구 회차의 연구 메타데이터는 요청이 아닌 참여 기록에서 읽고, product 회차는 기존 흐름을 유지한다.
- [x] 이름·전화번호·건강값·좌표가 참여기록 요청/응답/로그에 없다.
- [x] `./gradlew compileJava`, 관련 API 테스트, `bash scripts/harness/verify.sh --base <merge-base>`가 통과한다.

## 8. 영향 범위 / 마이그레이션

운영 DB에는 별도 migration SQL로 테이블과 FK를 추가한 뒤, 기존 `collection_run`의 중복 연구 메타데이터는 참여기록을 만들어 연결한 다음 제거한다. 데이터가 없는 개발 환경은 Hibernate가 새 모델을 생성한다. 삭제 DDL은 운영 데이터 백필과 함께 별도 승인 후 실행한다.

`study_participation.status`는 다른 연구 테이블(`collection_run` 등)과 같이 MySQL `ENUM`이 아니라 `VARCHAR(20)`로 만든다. 상태 값이 늘어도 `ALTER TABLE ... MODIFY COLUMN`이 필요 없다.

## 9. 미결정 사항 (Open Questions)

- 없음.

## 10. 참고

- 데이터정책서 v1.1 B장 1.5.1, 1.5.3, 1.5.8
- Issue #658
