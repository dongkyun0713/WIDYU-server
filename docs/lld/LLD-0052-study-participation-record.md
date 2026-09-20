# LLD-0052: 실증 참여 기록과 연구 회차 수집 게이트

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #658 |
| 관련 ADR | ADR-0034 (ADR-0026을 대체한다) |
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

보관 계획 수정은 `dataPolicy`와 날짜 셋을 **함께 넣거나 모두 비운다**. 넷 중 하나라도 있으면 넷이 모두 있어야 하고 `identifiedUntil <= pseudonymizedAt <= researchUntil`이어야 한다. 날짜만 있으면 무슨 규정으로 지우는지 알 수 없고, 정책만 있으면 언제까지인지 알 수 없다. 아직 IRB 승인이 없으면 넷을 모두 `null`로 둔다.

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
| `data_policy`, 날짜 3개 | NULL 허용 | IRB 확정 전 빈 값 허용. 넷이 전부 있거나 전부 비어 있어야 한다 |
| `status` | NOT NULL | `ACTIVE`, `ENDED`, `WITHDRAWN` |
| `withdrawn_at`, `withdrawal_scope`, `deletion_processed_at` | NULL 허용 | 철회 및 수동 처리 추적 |

선택 동의는 `study_participation_consent(study_participation_id FK, consent_key, granted)`로 저장한다. 일부 철회의 대상 항목은 `study_participation_withdrawal_item(study_participation_id FK, consent_key)`로 저장한다. 두 테이블은 직접식별자를 갖지 않으며, FK는 외부 식별자 `participation_id`가 아니라 내부 PK `study_participation_id`를 가리킨다.

같은 `study_id + member_id`의 `ACTIVE` 참여가 하나뿐이라는 규칙은 DB UNIQUE 제약이 보장한다. `ACTIVE`일 때만 `'1'`이고 그 외에는 `NULL`인 `active_key` 컬럼을 두고 `(study_id, member_id, active_key)`에 UNIQUE를 건다. `ACTIVE`가 아닌 행은 제약 대상에서 빠지므로 철회·종료한 참여는 여러 건 쌓인다. 회차의 `open_marker`와 같은 방식이다.

`active_key`는 **엔티티가 상태와 함께 채우는 일반 컬럼**이다. MySQL 생성 컬럼으로 두면 운영 스크립트로 만든 스키마에만 제약이 생기고, `ddl-auto`로 스키마를 만드는 dev·테스트 환경에는 제약이 없어 그 환경에서만 ACTIVE 중복이 샌다. 상태를 바꾸는 경로가 모두 한 메서드를 지나게 해 표식이 상태와 어긋나지 않도록 한다. 애플리케이션 조회 검사는 빠른 응답용이고, 동시 요청에서 UK에 걸린 쪽도 같은 409로 돌려준다.

`study_participation_history`는 등록·보관계획변경·철회·처리완료 시점마다 참여기록의 정책/상태 snapshot과 변경 종류·시각을 저장한다. 일부 철회로 거둔 항목은 `withdrawn_consent_keys`(JSON 배열 문자열)에 함께 복사해, 현행 행을 덮어써도 과거 보관 계획과 철회 범위가 사라지지 않게 한다.

`collection_run`에는 nullable `study_participation_id` FK를 둔다. 기존 `study_id`, `participation_id`, `consent_version`, 보관 날짜 컬럼은 연구 회차에서 쓰지 않으며 운영 마이그레이션으로 제거한다. 회차 응답·내보내기·자료 귀속의 연구 메타데이터는 FK를 따라 읽는다. 읽는 쪽마다 조건 분기를 두지 않도록 `CollectionRun`의 해당 getter가 참여 기록을 우선 읽고, 참여 기록이 없는 회차(`product` 회차, 이 기능 이전 회차)만 남은 컬럼을 읽는다. 참여 기록은 읽는 쪽의 트랜잭션 경계가 제각각이라 즉시 로딩한다.

## 5. 처리 흐름

### 등록

1. 관리자 요청의 회원 존재를 확인한다.
2. 서버가 `participation_id`를 발급하고 참여 기록을 `ACTIVE`로 저장한다.
3. 선택 동의와 `REGISTERED` snapshot을 같은 트랜잭션에서 저장한다.

동일 회원은 여러 연구 또는 시간상 여러 참여 기록을 가질 수 있다. 단, 같은 `study_id + member_id`의 `ACTIVE` 기록은 하나만 허용한다.

### 연구 회차 개설 게이트

1. `collectionMode`가 `research`이면 `participationId`가 없을 때 거절한다.
2. **회차를 저장하는 트랜잭션 안에서** 참여 기록을 `PESSIMISTIC_WRITE`로 잠그고 읽어(철회와 같은 행을 잠근다), 대상 회원과 일치하며 상태가 `ACTIVE`인지 확인한다. 검사와 저장이 다른 트랜잭션이면 그 사이에 들어온 철회를 놓쳐 `WITHDRAWN` 참여로 연구 회차가 열린다. 검사 메서드는 `MANDATORY` 전파로 호출자 트랜잭션을 요구한다.
3. 새 `CollectionRun`은 참여기록 FK만 저장한다. 연구 ID·동의 판·보관 값은 요청값을 신뢰하지 않고 참여기록에서 조회한다. FK는 같은 트랜잭션에서 잠금 재조회한 기록을 붙인다.
4. `product` 회차는 참여 기록 없이 기존처럼 개설한다.

### 상태 변경의 공통 규칙

보관 계획 수정·철회·삭제 처리 완료는 모두 참여 기록을 `PESSIMISTIC_WRITE`로 잠그고 읽은 뒤 상태를 재검증하고 갱신한다. 연구 회차 개설 게이트도 같은 잠금 조회를 쓴다. 잠그지 않고 읽으면 회차 개설과 철회가 서로의 결정을 보지 못하고 지나가, 이미 철회된 참여로 회차가 열리거나 철회가 두 번 기록된다. 읽기 전용 조회는 잠그지 않는다.

### 철회

1. `ACTIVE` 또는 `ENDED` 참여 기록만 철회할 수 있다. 잠금 뒤 이 검사를 하므로 동시 철회 중 하나만 통과한다.
2. 범위와 항목을 검증하고 상태·철회시각·범위를 갱신한다. 일부 철회의 각 항목은 그 참여가 실제로 받은(값이 `true`인) 선택 동의여야 한다 — 받지 않았거나 이미 거절한 항목은 거둘 것이 없다.
3. `WITHDRAWN` snapshot을 남긴다. 실제 데이터 삭제는 이 트랜잭션에서 하지 않는다.

### 관리자 변경 기록

무엇이 어떻게 바뀌었는지는 snapshot이 답하고, 누가·언제 바꿨는지는 기존 관리자 감사 로그(`AdminAuditLogService`)에 남긴다. 감사 로그는 참여 변경과 **같은 트랜잭션**에 저장한다(`logInCurrentTransaction`). 기존 `log()`의 `REQUIRES_NEW`를 쓰면 참여 변경이 롤백돼도 감사 로그만 남아, 실제로 바뀌지 않은 일이 바뀐 것처럼 기록된다. 등록·보관 계획 수정·철회·삭제 처리 완료가 대상이며, 감사 로그 detail에는 참여·회원·연구 식별자만 담는다. 동의 항목·보관 날짜·이름은 담지 않는다.

## 6. 예외 / 에러 처리

| 코드 | HTTP | 조건 |
| --- | --- | --- |
| `STUDY_PARTICIPATION_NOT_FOUND` | 404 | 식별자가 없음 |
| `STUDY_PARTICIPATION_DUPLICATED` | 409 | 같은 연구·회원의 ACTIVE 기록 |
| `STUDY_RETENTION_PERIOD_INVALID` | 400 | 날짜 일부 누락 또는 순서 역전 |
| `STUDY_PARTICIPATION_NOT_ACTIVE` | 400 | 연구 회차 개설에 ACTIVE가 아닌 기록 사용 |
| `STUDY_WITHDRAWAL_CONSENT_INVALID` | 400 | 일부 철회 대상이 비었거나, 받지 않았거나 거절한 동의 항목 |
| `RUN_RESEARCH_PARTICIPATION_REQUIRED` | 400 | 연구 회차에 참여 식별자 없음 |
| `RUN_RESEARCH_PARTICIPATION_MISMATCH` | 400 | 대상 회원과 참여 기록이 다름 |

## 7. 인수조건 (Acceptance Criteria)

- [x] 등록 시 서버 발급 `participation_id`, 연구 동의 판·서명일, 선택 동의가 저장·조회된다.
- [x] 보관 날짜와 `dataPolicy`가 **모두** 비어 있는 참여 기록을 등록할 수 있다.
- [x] `dataPolicy`와 날짜 셋 중 일부만 주거나 순서가 역전되면 거절한다.
- [x] 같은 연구·회원에게 ACTIVE 참여 기록을 두 번 만들 수 없다.
- [x] 보관 계획 수정·철회·처리 완료는 각각 불변 변경 이력을 남긴다.
- [x] 연구 회차는 ACTIVE 참여 기록 없이, 또는 다른 회원의 참여 기록으로 열 수 없다. 검사와 저장 사이에 철회가 들어와도 열리지 않는다.
- [x] 연구 회차의 연구 메타데이터는 요청이 아닌 참여 기록에서 읽고, product 회차는 기존 흐름을 유지한다.
- [x] 이름·전화번호·건강값·좌표가 참여기록 요청/응답/로그에 없다.
- [x] `./gradlew compileJava`, 관련 API 테스트, `bash scripts/harness/verify.sh --base <merge-base>`가 통과한다.

## 8. 영향 범위 / 마이그레이션

운영 DB에는 별도 migration SQL로 테이블과 FK를 추가한 뒤, 기존 `collection_run`의 중복 연구 메타데이터는 참여기록을 만들어 연결한 다음 제거한다. 데이터가 없는 개발 환경은 Hibernate가 새 모델을 생성한다. 삭제 DDL은 운영 데이터 백필과 함께 별도 승인 후 실행한다.

`study_participation.status`·`withdrawal_scope`와 이력의 `history_type`은 MySQL `ENUM`이 아니라 `VARCHAR`로 만든다. 값이 늘어도 `ALTER TABLE ... MODIFY COLUMN`이 필요 없기 때문이다. 다만 Hibernate 6은 MySQL에서 `@Enumerated(STRING)`을 native `ENUM(...)`으로 매핑하므로, 이 필드들에 `@JdbcTypeCode(SqlTypes.VARCHAR)`를 함께 붙여 생성 스키마와 `ddl-auto: validate`가 DDL의 `VARCHAR`와 어긋나지 않게 한다. 이 고정이 없으면 "값이 늘어도 ALTER가 필요 없다"는 설명 자체가 참이 아니다.

`admin_audit_log.action`은 사정이 다르다. 전용 생성 SQL이 없어 Hibernate가 만든 테이블이고, 같은 이유로 MySQL에서 `ENUM`일 수 있다. 새 `AdminAction` 값 넷을 쓰려면 배포 전에 `scripts/mysql/alter_admin_audit_log_action.sql`로 값 목록을 갱신한다. 감사 로그를 참여 변경과 같은 트랜잭션에 남기므로, 이 DDL을 빠뜨리면 감사 로그 INSERT 실패가 참여 변경까지 롤백시킨다. 운영에서 `SHOW COLUMNS FROM admin_audit_log LIKE 'action'`으로 실제 타입을 먼저 확인하고, `VARCHAR`면 건너뛴다.

`active_key`는 엔티티에 매핑된 일반 컬럼이라 `ddl-auto`가 만드는 dev·테스트 스키마에도 UNIQUE 제약이 함께 생긴다. 운영 DDL과 Hibernate 스키마가 같은 제약을 갖는다.

## 9. 미결정 사항 (Open Questions)

- 없음.

## 10. 참고

- 데이터정책서 v1.1 B장 1.5.1, 1.5.3, 1.5.8
- Issue #658
