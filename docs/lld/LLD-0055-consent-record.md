# LLD-0055: 인앱 동의 기록 (L1)

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #664 |
| 관련 ADR | [ADR-0036](../adr/ADR-0036-privacy-records-consent-access-logs.md) 결정 1·2·6 |
| 작성자 | Claude |
| 작성일 | 2026-09-21 |
| 선행 | 없음. base upstream/develop f454832 |

## 1. 목적 / 배경

앱이 항목별로 받은 서비스 동의의 버전·시각·철회를 서버가 기록한다(정책서 1.5.10). 서면 연구 동의는 `study_participation`(#658)이 담으므로 여기 넣지 않는다.

## 2. 범위

### In scope
- widyu-api / widyu-domain, 패키지 `com.widyu.consent`
- `consent_record` 테이블·엔티티, `ConsentKey` enum
- 회원 API 3개, 관리자 API 1개
- `ConsentService.isGranted(memberId, key)` (L2가 쓴다)

### Out of scope
- 동의 유무에 따른 기능 차단, 앱 화면, 서면 연구 동의, 자동 삭제

## 3. 인터페이스 / API

| 메서드·경로 | 호출자 | 요청 | 응답 |
| --- | --- | --- | --- |
| `PUT /api/v1/consents` | 회원 본인 | `{"version":"app-consent-v1","consents":{"PRIVACY_PERSONAL":true,"LOCATION":true,"LOCATION_NOTICE_BATCHED":false}}` | `ConsentStateResponse` |
| `GET /api/v1/consents` | 회원 본인 | — | `ConsentStateResponse` |
| `POST /api/v1/consents/withdrawal` | 회원 본인 | `{"keys":["LOCATION"]}` | `ConsentStateResponse` |
| `GET /api/v1/admin/members/{memberId}/consents` | 관리자 | — | `List<ConsentRecordResponse>` 전체 이력, 최근순 |

`ConsentStateResponse`: `{ "memberId": 1023, "items": [ {"key":"LOCATION","granted":true,"version":"app-consent-v1","recordedAt":"2026-09-21T10:00:00"} , … ] }` — 항목별 최신 행. 기록이 없는 항목은 목록에 없다.
`ConsentRecordResponse`: `{ "key", "granted", "version", "recordedAt", "source" }`.

`ConsentKey`(서버 enum, 앱은 이 값만 보낸다. 모르는 값 → 400 `CONSENT_KEY_INVALID`):

| 값 | 뜻(정책서 1.5.10 표) |
| --- | --- |
| `PRIVACY_PERSONAL` | 개인정보 수집·이용 |
| `PRIVACY_HEALTH` | 민감(건강)정보 수집·이용 — 다른 동의와 별도 |
| `LOCATION` | 위치정보 수집·이용 |
| `GUARDIAN_LOCATION_PROVIDE` | 보호자에게 위치 제공 |
| `LOCATION_NOTICE_BATCHED` | 위치 제공사실 통보를 모아서(최대 30일) 받는 데 동의. 없거나 false면 즉시 통보 |
| `RETENTION_NOTICE` | 보유 기간 고지 확인 |

## 4. 데이터 모델

`consent_record` (엔티티 `ConsentRecord`, widyu-domain). **추가 전용**: UPDATE·DELETE 없음.

| 컬럼 | 타입 | 제약 | 비고 |
| --- | --- | --- | --- |
| `consent_record_id` | BIGINT PK | | |
| `member_id` | BIGINT | NOT NULL, FK member | |
| `consent_key` | VARCHAR(40) | NOT NULL | `ConsentKey` STRING |
| `version` | VARCHAR(32) | NOT NULL | 앱이 보낸 동의문 판. 철회 행은 직전 행의 판을 복사 |
| `granted` | BOOLEAN | NOT NULL | false = 철회 또는 미동의 |
| `recorded_at` | DATETIME(6) | NOT NULL | 서버 시각 |
| `source` | VARCHAR(16) | NOT NULL | `APP` / `ADMIN`(이번엔 APP만) |

인덱스 `(member_id, consent_key, recorded_at DESC)`. `BaseTimeEntity` 대신 `recorded_at` 하나만(불변 행이라 updated_at 무의미).

`consent_key`·`source`는 **VARCHAR로 고정**한다(엔티티에 `@JdbcTypeCode(SqlTypes.VARCHAR)`). Hibernate 6은 MySQL에서 `@Enumerated(STRING)`을 native `ENUM(...)`으로 매핑해 운영 `ddl-auto: validate`와 어긋난다. VARCHAR면 항목 값이 늘어도 `ALTER TABLE`이 필요 없다.

## 5. 처리 흐름

- **제출**(`submit`): 요청 `consents` 각 항목마다 행 1개 INSERT(`granted` 요청값, `version` 요청값, `source=APP`). 같은 값이라도 기록한다(「언제 다시 동의했는지」도 사실). 한 트랜잭션. 응답은 최신 상태.
- **철회**(`withdraw`): `keys` 각각에 `granted=false` 행 INSERT. 직전 행이 없거나 이미 false여도 기록한다. `version`은 직전 행의 값, 없으면 `"-"`.
- **현재 상태**: 회원의 행을 `recorded_at DESC`로 읽어 항목별 첫 행만 취한다(QueryDSL 또는 JPQL 서브쿼리. 항목 6개·회원당 수십 행이라 전부 읽어 자바에서 접어도 된다 — 이쪽을 택한다).
- **`isGranted(memberId, key)`**: 최신 행이 있고 `granted=true`면 true. 없으면 false.
- 관리자 조회: 전체 행 최근순. 회원 존재 확인 후 없으면 404 `MEMBER_NOT_FOUND`(기존 코드).
- 로그: memberId·항목 수만. 항목 값·버전은 로그에 남기지 않아도 되지만 개인정보는 아니다. 이름·전화번호 금지.

## 6. 예외 / 에러 처리

| 코드 | HTTP | 조건 |
| --- | --- | --- |
| `CONSENT_KEY_INVALID` (`CONSENT_4000`) | 400 | enum 밖 키 |
| `CONSENT_REQUEST_EMPTY` (`CONSENT_4001`) | 400 | `consents`·`keys`가 비어 있음 |

## 7. 인수조건 (Acceptance Criteria)

- [x] 항목 3개를 제출하면 행 3개가 `version`·`recorded_at`·`source=APP`으로 저장되고 현재 상태 응답에 3개가 있다.
- [x] 같은 항목을 다시 제출하면 행이 늘고 현재 상태는 최신 값이다.
- [x] 철회하면 `granted=false` 행이 생기고 `isGranted`가 false가 된다. 직전 판이 복사된다.
- [x] 기록 없는 항목의 `isGranted`는 false다.
- [x] 모르는 키·빈 요청은 400.
- [x] 관리자 이력 조회는 전체 행을 최근순으로 주고, 응답에 이름·전화번호가 없다.
- [x] `./gradlew compileJava`, `run-module-tests.sh`, `verify.sh --base` 통과.

## 8. 영향 범위 / 마이그레이션

`scripts/mysql/create_consent_record.sql`. ERD `consent_record`. `backend/CLAUDE.md` `consent` 절.

## 9. 미결정 사항 (Open Questions)

- 없음.

## 10. 참고
`MemberNotificationSetting`(회원별 설정 선례), `AdminMemberController`(관리자 회원 경로), `SecurityUtil.getCurrentMemberId()`.
