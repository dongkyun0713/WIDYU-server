# LLD-0074: 사건의 두 라벨 축과 판독 기록

> Low-Level Design. 이 문서는 이슈 #716의 판독 저장 구조와 PR 검수 기준이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review (2026-10-02 구현·검증 완료, 코디네이터 검수 대기) |
| Issue | #716 |
| 관련 ADR | [ADR-0038](../adr/ADR-0038-self-check-first-safety-flow.md) 결정 2·4·6 |
| 선행 | LLD-0073(원답 증거), LLD-0072, LLD-0054 |
| 작성자 | Codex / feature/716 |
| 작성일 | 2026-10-02 |

## 1. 목적 / 배경

기존 `IncidentOutcome`은 보호자가 운영상 사건을 종결할 때 쓰는 단일 값이다. 실제 사건 존재와 당시 도움 필요 여부는 독립된 판단이므로 `incident_label`에 두 축을, `label_annotation`에 판독 주체·채점표·원래 판독값을 보존한다. 시니어 원답·운영 outcome·판독 결과를 자동으로 서로 변환하지 않는다.

## 2. 범위

### In scope

- `widyu-domain`: `com.widyu.incident.IncidentLabel`·`LabelAnnotation`과 두 축·판독 주체 enum. 기존 `Incident`, `IncidentOutcome`은 유지한다.
- `widyu-api`: 두 테이블의 Repository와 내부 `IncidentLabelService` 기록 메서드·테스트. 관리자 HTTP API는 만들지 않는다.
- 운영 DDL과 [ERD-0001](../erd/ERD-0001-initial-domain.md)의 관계·인덱스·마이그레이션 이력.

### Out of scope

- 판독 입력 UI·관리자 API, 실제 LLM 호출·외부 반출, 채점표 배포, 임상 확정·adjudication 절차.
- 기존 `resolve()`/`IncidentOutcome` 쓰기 경로 변경, 과거 outcome→두 축 자동 백필, 원답→라벨 자동 추론, 연구 export·학습 적격 판정.

## 3. 인터페이스 / API

이번 변경에 새 HTTP API는 없다. 내부 서비스 경계는 아래 두 메서드다. 입력은 인증된 내부 호출자가 전달하며 내부용 command는 `of()` 팩토리를 사용한다.

| 메서드 | 입력 | 결과 |
| --- | --- | --- |
| `recordAnnotation(command)` | `incidentRef`, `annotatorType`, `reviewerId` 또는 `annotatorRef`, `rubricVersion`, 두 축, `note`, `sourceEvidenceRef`, 선택 `supersedesAnnotationId` | 불변 annotation ID·revision·서버 시각 |
| `setCurrentLabel(incidentRef, annotationId, expectedCurrentAnnotationId)` | 채택할 같은 사건 annotation과 호출자가 본 현재 판독 ID(null 가능) | 현재 두 축·`source`·`labeledAt` |

HUMAN은 인증된 활성 `ADMIN` 회원 ID가 필요하다. 서비스는 `SecurityUtil`의 현재 회원 ID와 `reviewerId`의 일치·회원 역할·활성 상태를 검사한 뒤 기록한다. LLM_JUDGE는 내부 서비스 principal와 judge run 식별자를 `annotatorRef`에 기록하며 `reviewerId=NULL`이다. 모델에 사람 ID·면허 자격을 주지 않는다. `setCurrentLabel`은 명시적 호출에서만 동작한다. `recordAnnotation`, 원답 저장, 기존 `resolve()`는 현재 라벨을 자동으로 채택하지 않는다.

## 4. 데이터 모델

엔티티는 `widyu-domain`, Repository·Service는 `widyu-api`에 둔다. enum 열은 `@Enumerated(STRING)` + `@JdbcTypeCode(SqlTypes.VARCHAR)`이고 운영 DDL도 `VARCHAR`다. 시간은 UTC epoch milliseconds다.

| 테이블·열 | 제약·의미 |
| --- | --- |
| `incident_label.id` | `BIGINT` PK |
| `incident_ref` | `VARCHAR(40) NOT NULL`, UK. 기존 사건과 1:0..1 연결 |
| `event_occurrence` | `VARCHAR(24) NOT NULL`: `PRESENT|ABSENT|UNDETERMINED|NOT_ASSESSED` |
| `help_need` | `VARCHAR(24) NOT NULL`: `NEEDED|NOT_NEEDED|UNDETERMINED|NOT_ASSESSED` |
| `labeled_at` | `BIGINT NULL`; 명시적 채택의 서버 시각. 미판독 초기 행은 NULL |
| `source` | `VARCHAR(24) NOT NULL`: `HUMAN|LLM_JUDGE|UNREVIEWED|LEGACY_UNREVIEWED`. 신규 초기 행은 `UNREVIEWED`. Java enum에 `@JdbcTypeCode(SqlTypes.VARCHAR)` |
| `current_annotation_id` | `BIGINT NULL`; 현재 채택 annotation ID, 비교 후 갱신 조건. 초기 행은 NULL |
| `label_annotation.id`, `label_id` | `BIGINT` PK·FK. label 1건에 불변 판독 이력 여러 건 |
| `annotator_type` | `VARCHAR(24) NOT NULL`: `HUMAN|LLM_JUDGE` |
| `rubric_version` | `VARCHAR(64) NOT NULL`, 판독 당시 채점표 판본 |
| `reviewer_id`, `annotator_ref` | HUMAN은 `reviewer_id BIGINT NOT NULL` 의미·`annotator_ref NULL`; LLM은 `reviewer_id NULL`·`annotator_ref VARCHAR(128) NOT NULL` 의미 |
| `event_occurrence`, `help_need` | 해당 annotation의 두 축 원값. 현재 label 변경에도 보존 |
| `note`, `source_evidence_ref` | `TEXT NULL`, `VARCHAR(128) NULL`; 짧은 근거와 증거 묶음 참조. 원자료 복제를 피함 |
| `revision`, `supersedes_annotation_id` | `INT NOT NULL`, `BIGINT NULL`; 정정은 새 행으로 연결 |
| `created_at` | `BaseTimeEntity`의 서버 `LocalDateTime` 저장 시각. 과거 사건 시각으로 소급하지 않음 |

`UK(label_id,revision)`과 `idx_label_annotation_reviewer(reviewer_id,created_at)`를 둔다. `incident_label`은 명시적 채택 결과를 빠르게 읽는 행이고 annotation은 변경 불가한 증거다. 첫 annotation 전에 label 행을 만들면 두 축은 `NOT_ASSESSED`, `source=UNREVIEWED`, `labeled_at/current_annotation_id=NULL`이다. 행이 없는 과거 사건도 미판독이며 기존 outcome에서 새 축을 추론하지 않는다. 과거 자료를 별도 표시할 때만 `LEGACY_UNREVIEWED`를 쓴다. LLM 판독을 기록할 수 있어도 자동으로 `source=LLM_JUDGE`인 현재 라벨이 되는 것은 아니다.

A §4.2의 채점표 ID/hash, 사건 정의·라벨 스키마 판본, 축별 certainty·confidence, evidence bundle/hash, review status·가용 시각·LLM provenance는 연구 판독 계약이 확정될 때 확장한다. 이 최소 구조만으로 임상 확정이나 연구 export 적격을 주장하지 않는다.

## 5. 처리 흐름

1. `recordAnnotation`은 별도 트랜잭션에서 `incident_ref`가 실제 사건인지 확인하고 label 행을 잠근다. 행이 없으면 미판독 초기 행을 만들며 `UK(incident_ref)`로 동시 생성을 수렴시킨다. 판독자 식별·채점표 판본·각 축 enum·정정 대상의 동일 label 소속을 검증한 후 다음 revision의 annotation을 삽입한다. 원답·운영 outcome·현재 label의 두 축은 바꾸지 않는다.
2. `setCurrentLabel`은 label 행을 잠그고 annotation이 같은 label에 속하는지 확인한다. 저장된 `current_annotation_id`와 `expectedCurrentAnnotationId`가 다르면 충돌 오류다. 일치하면 annotation의 두 축을 각각 복사하고 source를 판독 주체로 설정하며 서버 시각을 `labeled_at`에 기록한다. `(PRESENT,NOT_NEEDED)`와 `(ABSENT,NEEDED)`도 유효하다.
3. 기존 `IncidentService.resolve()`는 LLD-0054대로 `IncidentRepository.resolve`의 조건부 UPDATE로 `IncidentOutcome`, `resolved_by`, `resolved_at_ms`를 계속 기록한다. 새 label 서비스는 이 경로를 호출하지 않고, `resolve()`도 새 label에 쓰지 않는다. `TRUE_EMERGENCY→(PRESENT,NEEDED)` 같은 매핑은 없다.
4. 정정은 기존 annotation 행 수정이 아니라 새 revision과 `supersedes_annotation_id`로 기록한다. 사람이 judge 결과를 검토해도 새 HUMAN 행을 만든다. 판독 간 불일치를 다수결로 자동 제거하지 않는다. 이번 범위에는 실제 LLM 호출이 없다.

## 6. 예외 / 에러 처리

| 조건 | 처리 |
| --- | --- |
| 사건 없음·다른 사건의 annotation 또는 정정 대상 | 도메인 오류; 새 label·annotation 변경 없음 |
| HUMAN의 `reviewerId` 누락, LLM의 `annotatorRef` 누락, 채점표 판본·축 값 오류 | 유효성 오류; annotation 없음 |
| HUMAN `reviewerId`가 인증 회원과 다르거나 활성 ADMIN이 아님 | `AUTH_4030`; annotation 저장 없음 |
| 최초 label 생성·revision 동시 경합 | UK 또는 행 잠금으로 한 행·연속 revision에 수렴; 실패 트랜잭션은 롤백 |
| 오래된 `expectedCurrentAnnotationId`로 채택 | 충돌 오류; 이전 채택 유지 |
| 기존 `resolve()` 중복 | 기존 `INCIDENT_4091` 유지; 새 테이블 변경 없음 |

## 7. 인수조건 (Acceptance Criteria)

- [ ] AC1. `IncidentOutcome.TRUE_EMERGENCY|FALSE_ALARM|UNKNOWN`과 `IncidentService.resolve()` 쓰기 경로는 그대로 동작한다. resolve·OK·후속 답변만으로 새 label/annotation은 0건이다.
- [ ] AC2. 두 축의 `(PRESENT,NOT_NEEDED)`·`(ABSENT,NEEDED)`·`UNDETERMINED`·`NOT_ASSESSED`를 독립 저장·조회한다. 과거 outcome에서 양성·음성을 자동 추론하지 않는다.
- [ ] AC3. HUMAN 판독은 인증된 `reviewer_id`와 채점표 판본·note·서버 `created_at`을, LLM_JUDGE 판독은 서비스/judge 식별과 `reviewer_id=NULL`을 보존한다. judge 행은 사람 검토·임상 확정으로 표시하지 않는다.
- [ ] AC4. annotation 정정은 새 revision이며 이전 원값은 유지한다. 현재 label은 명시적 채택에서만 바뀌고, 다른 사건 annotation이나 오래된 현재 revision에 의한 채택은 실패한다.
- [ ] AC5. 운영 DDL·ERD에 두 테이블·UK·인덱스를 반영한다. `./gradlew compileJava`, `bash scripts/harness/run-module-tests.sh domain`, `bash scripts/harness/verify.sh`가 통과한다. H2 성공을 MySQL ENUM 검증으로 보지 않는다.

## 8. 영향 범위 / 마이그레이션

`scripts/mysql/create_incident_label.sql` 한 파일에 두 테이블을 담아 W11b DDL(`alter_incident_ok_notice_grouping.sql`) 뒤·새 앱 배포 전 적용한다. 기존 `incident` 테이블, `IncidentOutcome` enum, `resolve()` API는 변경하지 않는다. 과거 행은 자동 백필하지 않는다. 후속 연구 소비자는 과거 미판독을 `NOT_ASSESSED/LEGACY_UNREVIEWED`로 별도 표시하고 구 export와 버전을 구분해야 한다. MySQL native ENUM 수정은 없다.

## 9. 미결정 사항 (Open Questions)

입력 UI는 범위 밖. 채점표 ID/hash, 판독자 역할·자격, 증거 bundle 지문, 축별 certainty·confidence, LLM 처리 위치·반출·동의, 사람 검토·adjudication 및 연구 export 적격은 A §4.2와 RECONCILIATION R2의 후속 결정이다. 이 구조를 저장할 수 있다는 사실만으로 LLM 실행이나 임상 gold 라벨 사용을 허용하지 않는다.

## 10. 참고

- A-LABEL-QUALITY-SPEC-v0.1 §1·§3·§4.1·§4.2, RECONCILIATION §2·§6 R1/R2, 코드 변경점 표 A3·A4.
- [LLD-0073](LLD-0073-followup-card-and-answers.md), [LLD-0072](LLD-0072-ok-notice-and-incident-grouping.md), [LLD-0054](LLD-0054-incident.md) §5.4.
