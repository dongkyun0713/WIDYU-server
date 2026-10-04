# LLD-0071: 안심구역 이탈을 안전 사건으로 처리

> Low-Level Design. 이 문서는 이슈 #707 구현과 PR 본문의 오라클이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review (2026-10-02 구현 완료, 코디네이터 최종 검토 대기) |
| Issue | #707 |
| 관련 ADR | ADR-0038 결정 1·2·6·7, ADR-0037 결정 1·4·6 |
| 작성자 | Codex |
| 작성일 | 2026-10-02 |

## 1. 목적 / 배경

현재 안심구역 이탈은 Redis 30분 키로 중복을 막고 보호자에게 즉시 푸시한다. 심박 위급과 같은 사건·시니어 본인확인 흐름을 적용해 이탈마다 사건을 열고, 같은 이탈이 이어지는 동안 사건과 알림을 늘리지 않는다. 재진입은 상황 종료 시각으로 기록해 다음 이탈을 새 사건으로 처리한다.

## 2. 범위

### In scope

- 변경 모듈: `widyu-domain`의 `IncidentKind`, `widyu-api`의 안전구역 위치 전이·이벤트 리스너·사건/알림 서비스와 저장소, 관련 테스트. 구현 시 ERD의 `IncidentKind` 값 목록도 갱신한다.
- `SafeZoneExitEvent`의 소비자를 사건 경로 하나로 바꾸고 S02·S05 문구를 정본 `NotificationCopy`에서 가져온다.
- Redis `safezone:alert:{memberId}` 30분 키의 생성·조회·삭제 및 `releaseAlertOnRollback`을 제거한다. Redis `location:stay:{memberId}`와 커밋 후 체류 정보 저장은 유지한다.
- 안심구역 재진입 시 열린 `SAFE_ZONE_EXIT` 사건의 `situation_ended_at_ms`를 기록한다. 상태는 바꾸지 않는다.

### Out of scope

- 위치 반경·WebSocket 응답·위치 이력·브로드캐스트 계약 변경.
- S09 정보성 알림과 시니어 취소 응답의 공통 처리 변경은 후속 W11b에서 한다. 자동전화·최종 알림 S07도 이 LLD의 구현 범위 밖이다.
- `FcmCategory` 값 추가, 새로운 Redis 중복 키, FCM outbox 재시도 정책 변경.

## 3. 인터페이스 / API

새 HTTP API는 없다. 기존 WebSocket `/app/location/update`, 보호자 `/topic/location/senior/{memberId}`, 사건 조회·응답 API의 경로와 JSON 계약은 유지한다. 안심구역 사건은 기존 사건 응답의 `kind=SAFE_ZONE_EXIT`, `decisionId=null`, `runId=null`로 나타난다.

| 이벤트 / 전달 | 계약 |
| --- | --- |
| `SafeZoneExitEvent(seniorMemberId)` | 안전구역 안에서 밖으로 이동한 위치 갱신의 DB 커밋 뒤에 처리한다. |
| 시니어 본인확인 | `NotificationType.SAFETY_SELF_CHECK`, 문구 S02, `incidentRef`로 화면을 연다. 버튼은 `취소` 하나, 마감은 서버 사건 개시 시각 + 60초다. 센터 행은 만들지 않는다. |
| 보호자 최초 알림 | `NotificationType.SAFE_ZONE_EXITED`, 문구 S05, `eventId=incidentRef`, `seniorId`와 [LLD-0076](LLD-0076-guardian-deeplink-paths.md)의 `/location?seniorId={seniorId}` 템플릿을 사용한다. 수신자별 센터 행과 outbox는 W11a-1의 공통 경로를 따른다. |
| 시니어 정상 응답 뒤 안내 | S09는 W11b의 `OK_NOTICE` 경로가 맡는다. 본 LLD는 `SAFE_ZONE_EXIT` 종류로 그 경로에 들어갈 수 있게 한다. |

## 4. 데이터 모델

| 대상 | 변경 |
| --- | --- |
| `IncidentKind` (`widyu-domain`) | `SAFE_ZONE_EXIT` 추가. 제품 이벤트 `SAFE_ZONE_EXITED`에 대응한다. 기존 `FALL_SUSPECTED`를 재사용하지 않는다. |
| `incident.kind` | 이미 `@Enumerated(STRING)`과 `@JdbcTypeCode(SqlTypes.VARCHAR)`가 적용된 `VARCHAR(32)`이다. 새 값에 대한 DDL은 없다. |
| `incident.decision_id`, `run_id` | 안심구역 사건에서는 둘 다 `NULL`이다. W11a-1의 nullable 스키마를 재사용한다. |
| `incident.situation_ended_at_ms` | 재진입 시 열린 안심구역 사건에 서버 시각을 기록한다. W11a-1의 기존 컬럼을 재사용한다. |

새 테이블·컬럼·Redis 키는 없다. `FcmCategory`는 추가하거나 변경하지 않는다. H2는 운영 MySQL native ENUM 호환성의 증거로 사용하지 않는다.

## 5. 처리 흐름

1. `RealtimeLocationService`가 기존 가족 검증과 위치 저장을 수행하고, 기존 반경 판정 뒤 이전·현재 안심구역 타입을 `SafeZoneAlertService`에 전달한다. 같은 위치 반경에서 조기 반환하는 경로는 전이가 없으므로 사건을 조회하지 않는다. 체류 정보는 LLD-0058대로 위치 트랜잭션 커밋 뒤 저장한다.
2. `SafeZoneAlertService`는 Redis 알림 키를 읽거나 쓰지 않는다. **`밖→안` 전이(`previousLocationType == null && currentLocationType != null`)일 때만** 위치 갱신 트랜잭션에서 `MemberRepository.findByIdForUpdate`로 회원 행을 잠근 다음 `IncidentRepository`의 조건부 UPDATE 한 건으로 `situation_ended_at_ms IS NULL`인 `SAFE_ZONE_EXIT` 사건의 종료 시각을 기록한다. 잠금 순서는 리스너와 같은 회원 행 → incident다. `CHECKING`·`ESCALATED`뿐 아니라 `OK_CLOSED`·`RESOLVED`에도 적용하며 사건 상태와 시니어 응답·보호자 알림 기록은 변경하지 않는다. `안→안`·`밖→밖` 갱신에서는 사건을 조회하거나 갱신하지 않는다. 이탈(`previousLocationType != null && currentLocationType == null`)이면 `SafeZoneExitEvent`를 발행하고, 리스너의 회원 행 잠금·최신 체류 정보 조회·종료 시각 조회가 최종 중복 여부를 결정한다. 초기 위치가 이미 구역 밖이면 이탈로 간주하지 않는다. `RealtimeLocationService`는 `location:stay:{memberId}`의 커밋 후 저장 콜백을 이탈 이벤트보다 먼저 등록해 정상 이탈 리스너가 새 체류 정보를 읽게 한다.
3. `SafeZoneNotificationListener`는 `@TransactionalEventListener(phase = AFTER_COMMIT)`와 `@Transactional(propagation = REQUIRES_NEW)`로 이벤트를 처리한다. 위치 트랜잭션이 롤백되면 사건이나 푸시를 만들지 않는다. 기존 가족 대상 직접 `FcmService` 루프는 삭제한다.
4. 리스너는 `IncidentService.openForAlert(seniorMemberId, SAFE_ZONE_EXIT)`를 호출한다. 판정 기록 없이 사건을 열고 `decision_id`·`run_id`는 비워 둔다. 회원 행을 `findByIdForUpdate`로 잠근 뒤 `RealtimeLocationService`가 이전 위치 타입 계산에 쓰는 `location:stay:{memberId}`를 다시 읽는다. 최신 `currentLocationType != null`이면 이미 안에 있으므로 debug 로그를 남기고 사건·S02·S05 없이 반환한다. 밖이면 같은 회원·kind에 **`situation_ended_at_ms IS NULL`인 사건을 상태와 무관하게** 조회한다. 안심구역은 재진입 전까지 같은 이탈이므로 심박용 5분 `opened_at_ms` 하한을 적용하지 않는다. `OK_CLOSED`·`RESOLVED`라도 재진입 전이면 기존 사건을 반환하고 S02·S05를 다시 만들지 않는다. 없으면 새 사건을 저장하고 S02를 `SAFETY_SELF_CHECK` 푸시 전용으로 한 번 enqueue한다. 심박 경로는 위치 조회 없이 기존 조건을 유지한다.
5. `sensor.incident.self-check-first=false`이면 `IncidentService`가 **새 `SAFE_ZONE_EXIT` 사건을 생성한 분기에서만** W11a-1의 `IncidentEscalation.sendImmediately`를 호출한다. 열린 사건을 재사용하는 분기는 플래그가 바뀌어도 S02·S05를 다시 만들지 않는다. 플래그가 `true`이면 60초 무응답 뒤 기존 스케줄러가 같은 경로를 실행한다. `IncidentEscalation.enqueueInitialAlert` 한 곳에서 kind별로 `HR_ANOMALY→HEART_RATE_EMERGENCY/S04`, `SAFE_ZONE_EXIT→SAFE_ZONE_EXITED/S05`를 조립한다. 기존 `findDueIds`·무응답 상태 전환 UPDATE·최초 알림 게이트 UPDATE(W11a-1-fix3)를 안심구역 kind까지 확장하고 `initial_alert_sent_at_ms IS NULL` 게이트를 유지한다. 새 스케줄러나 게이트 컬럼은 만들지 않는다.
6. 이미 밖인 상태의 반복 위치 갱신은 새 이탈 이벤트를 만들지 않는다. 체류 정보 재시도 등으로 같은 이벤트가 다시 들어와도 4단계의 잠금과 열린 사건 조회로 새 사건·S02·S05가 생기지 않는다. 재진입 뒤 다음 `안→밖` 이동은 종료 시각이 있는 이전 사건과 별개의 사건을 연다.

트랜잭션 경계는 위치 갱신 커밋 → `AFTER_COMMIT` 리스너의 새 트랜잭션 → 사건·S02·플래그 OFF S05의 원자적 enqueue다. 도메인 간 알림은 이벤트를 통하고, 보호자 수신자·설정·센터 저장은 공통 사건/FCM 경로가 처리한다.

## 6. 예외 / 에러 처리

- 새 HTTP/WebSocket 에러 코드는 없다. 위치 갱신 롤백 시 `AFTER_COMMIT` 리스너가 실행되지 않는다.
- 사건 열기나 enqueue가 새 트랜잭션에서 실패하면 그 트랜잭션의 사건·outbox가 함께 롤백된다. 이미 커밋된 위치와 체류 정보는 되돌아가지 않는다. 이때 원래 `안→밖` 경계가 다시 오지 않으면 알림 재시도는 보장되지 않는다. 장애 재시도/outbox 보강은 별도 범위로 기록한다.
- 재진입 종료 UPDATE는 열린 `SAFE_ZONE_EXIT`와 `situation_ended_at_ms IS NULL`에만 적용해 반복 재진입과 후속 사건에 영향을 주지 않는다. 사건이 없으면 변경 0건이다.
- 이탈 커밋 뒤 리스너보다 재진입이 먼저 회원 행 잠금을 잡으면 종료 UPDATE가 0건이어도 리스너는 잠금 뒤 최신 체류 정보가 안임을 확인해 사건을 열지 않는다. 리스너가 먼저 잠금을 잡으면 사건을 연 뒤 재진입이 대기에서 풀려 그 사건의 종료 시각을 기록한다. 두 경로의 잠금 순서는 회원 행 → incident로 동일하다.
- FCM 실제 전달은 outbox의 at-least-once 범위다. 인수조건의 “한 번”은 사건·enqueue/센터 행 기준이며 provider 전달 exactly-once를 뜻하지 않는다.

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1. 안심구역 `안→밖` 한 번에 `SAFE_ZONE_EXIT` 사건 1건(`decision_id=NULL`)과 S02 `SAFETY_SELF_CHECK` enqueue 1건이 생긴다. 기존 가족 직접 푸시는 0건이다.
- [x] AC2. `self-check-first=false`에서 같은 이탈에 S05 `SAFE_ZONE_EXITED` 최초 알림이 수신 보호자당 즉시 1건 enqueue되고 `initial_alert_sent_at_ms`가 기록된다. `true`에서는 60초 전 S05가 0건이고 마감 뒤 공통 게이트로 수신 보호자당 1건이다.
- [x] AC3. 이탈 중 반복 위치 갱신 또는 중복 이벤트에서 사건·S02·S05 추가 건수는 모두 0이다. `OK_CLOSED`·`RESOLVED` 뒤라도 재진입 전이면 같은 사건을 재사용한다. 회원 행 잠금 아래 동시 중복 진입도 사건 1건으로 수렴한다.
- [x] AC4. `previousLocationType == null && currentLocationType != null`인 재진입만 `situation_ended_at_ms IS NULL`인 안심구역 사건의 종료 시각을 상태와 무관하게 채우고 상태는 바꾸지 않는다. `OK_CLOSED` 사건도 종료 시각을 받는다. `안→안`·`밖→밖`은 사건을 조회하지 않으며 반복 재진입은 시각을 덮지 않는다.
- [x] AC5. 재진입 후 다시 이탈하면 새 `incidentRef` 사건 1건과 새 S02가 생긴다. 플래그 OFF면 새 S05도 즉시 생긴다. 앞선 사건의 Redis 30분 TTL 때문에 차단되지 않는다.
- [x] AC6. `safezone:alert:{memberId}`를 읽거나 쓰지 않고 `releaseAlertOnRollback`이 제거된다. 위치 트랜잭션 롤백은 사건·S02·S05를 만들지 않는다.
- [x] AC7. S02·S05는 문구표 v0.4를 사용하고, `FcmCategory` 값 추가나 새 `incident.kind` DDL 없이 동작한다. 관련 테스트는 JUnit 5·Mockito(BDDMockito `given`/`willReturn`), 한글 언더스코어 메서드명과 `<행위>하면 <결과>한다` DisplayName을 사용하며 상태 변화를 우선 검증한다.
- [x] AC8. `bash scripts/harness/verify.sh`가 통과한다. 도메인 enum 변경 후 `./gradlew compileJava`와 Domain+API 테스트를 실행하고 ERD의 `IncidentKind` 값 목록을 갱신한다.

## 8. 영향 범위 / 마이그레이션

- LLD-0011의 `safezone:alert` 1800초 키 기반 중복 차단과 LLD-0058의 `releaseAlertOnRollback` 설계를 이 문서가 대체한다. LLD-0058의 체류 정보 커밋 후 저장은 유지한다. 키를 남기면 OK 뒤 10분 재이탈도 막혀 새 사건이 열리지 않으므로 제거가 필수다. 기존 Redis 키가 남아 있어도 더 이상 읽지 않으며 TTL 만료에 맡긴다.
- 중복 판정이 Redis에서 `incident`의 열린 사건 조회로 이동한다. **사건 조회는 이탈 전이의 리스너에서만** 한 건 늘어난다. 재진입 전이에는 조건부 UPDATE가 한 건 늘어나며 `안→안`·`밖→밖`과 같은 위치 반경 갱신에는 사건 조회가 없다. 회원 행 잠금은 사건 생성의 동시성 경계이므로 동일 회원의 동시 이탈은 직렬화된다.
- 이탈 리스너는 회원 행 잠금 뒤 최신 `location:stay` 조회가 한 건 추가된다. 재진입 전이는 회원 행 잠금 조회가 한 건 추가된다. `AFTER_COMMIT` 콜백 순서는 코드에서 체류 저장을 이벤트보다 먼저 등록하도록 고정했지만, 실제 Redis 저장과 서로 다른 위치 갱신 트랜잭션의 잠금 해제 사이에 있는 극히 짧은 경합은 `@DataJpaTest`/mock Redis로 재현하지 못했다. 다중 트랜잭션·실제 Redis 경합 검증은 후속 통합 테스트로 남긴다.
- `kind`가 이미 `VARCHAR(32)`이므로 `SAFE_ZONE_EXIT` 추가 DDL은 없다. 구현 단계에서 ERD의 값 목록을 수정한다. H2 통과만으로 운영 MySQL 컬럼 타입을 확인했다고 보지 않는다.
- `self-check-first`는 기본 false다. FE 확인 화면 배포 전에는 S02와 함께 S05가 즉시 나가는 현행 체감 동작을 유지하고, FE 배포 확인 후 운영에서 true로 전환한다.

## 9. 미결정 사항 (Open Questions)

아래는 전체 계획 §5의 회신 대기 항목과 작업 가정이다. 회신 전에는 표의 가정을 적용한다. 이 LLD에서 직접 쓰는 T1·T2·같은 사건 묶기·S10 항목도 미결정으로 남긴다.

| 항목(출처) | 서버 답/제안 | 구현 가정 | 막히는 W |
|---|---|---|---|
| T1-① 1분 기준 시각·경계 | 기준=서버 사건 생성 시각, 마감=+60s, 정확히 마감=초과(B §3 제안 수용) | 그대로 | — |
| T1-② HELP 값 유지 | 유지 제안(워치 호환, ESCALATED → 스케줄러가 INITIAL_ALERT) | 유지 | — |
| T1-③ FCM 전체 실패 시 동작·최장 대기 | 본인확인 푸시 5분 TTL 소진 시 `DELIVERY_FAILED` 표시, **INITIAL_ALERT는 그대로 1분에 발송**(시니어 미수신이 보호자 알림을 미루지 않음) | 그대로 | — |
| T1-④ 5분 기준 시각 | `initial_alert_sent_at_ms` = 스케줄러 enqueue 시각(ADR-0035 결정 3과 같은 근사. provider 수락 시각이 필요하면 `finish` 첫 성공에서 덮는 후속) | 그대로 | W12 |
| S10 표시 시간 | FE·제품 몫 | — | — |
| T2 Incident kind ↔ 제품 이벤트 | `HR_ANOMALY↔HEART_RATE_EMERGENCY`, `SAFE_ZONE_EXIT↔SAFE_ZONE_EXITED`, `FALL_SUSPECTED`는 범위 밖(AI 입구 꺼짐) | 그대로 | — |
| T2 run 없는 경로 | `decision_id` null 허용, `incidents.jsonl`에는 run 귀속 사건만 | 그대로 | — |
| T3 자동전화 제공자·콜백·푸시 OFF 비방장 | 미정 → 구현 보류 | — | **W12 전면** |
| BE 발송본 5 「같은 사건 묶는 범위」 | 열린 사건 + 마지막 감지 5분 창, 안심구역은 재진입으로 종료 | ADR-0038 S-D6 | — |
| BE 발송본 6 「판단 단위 저장」 | 이미 저장됨(`heart_rate_event.status` 샘플별) | — | — |
| ADR-0035 결정 3 「알림=FCM 성공」 | INITIAL_ALERT 기준으로 좁힘, 60초 안 OK는 `alert_delivered=false`가 정상 | S-D10 | — |
| W02 적용 시작일 | 현행 「다음 날」 유지, 문구에 넣지 않음 | 그대로 | — |
| 복약 동기화 신호 형식 | 항상 DATA_ONLY `MEDICATION_SCHEDULE_SYNC`(revision) 동반 + 구 앱 호환 `type=MEDICATION_SCHEDULE_CHANGED` 유지 | 그대로 | — |
| 시니어 푸시 설정 항목 | `GENERAL` 하나 | 그대로 | — |
| priority·channel·deepLink 문자열(문서에 없음) | LLD-W2에 제안표 수록 | 제안값 | — |
| G02 동시 달성 묶음 | 단일 트리거가 목표 2개를 동시에 적립하는 경로가 없어 G01만 발생 | G01만 | — |
| A 활성화 조건 | 카드·10p는 플래그 OFF로 머지, IRB 확인 뒤 ON | — | — |

S05의 2026-10-02 임시 경로는 [LLD-0076](LLD-0076-guardian-deeplink-paths.md)에 따라 2026-10-03 FE 문서의 `/location?seniorId={seniorId}`로 교체·확정했다. 구현은 `NotificationType.SAFE_ZONE_EXITED`의 딥링크 정의 한 곳을 사용한다.

2026-10-02 구현 시 위 가정표를 유지했다. S09·자동전화는 후속 작업이며, 본 범위에서 새 정책 결정을 추가하지 않았다.

ADR-0038 S-D6의 ‘OK = 상황 종료’는 안심구역에서는 재진입으로 대체한다. 시니어가 OK로 답해도 밖에 머무는 동안은 같은 상황이며, `situation_ended_at_ms`가 기록된 뒤에만 재이탈을 새 사건으로 본다.

## 10. 참고

- ADR-0038 결정 1·2·6·7; ADR-0037 결정 1·4·6; LLD-0011·LLD-0058(대체 범위); W11a-1 사건 경로.
- 본인확인 명세 B §2·§3, `FE-HANDOFF-v0.4` §5.2, `HEMLO-REVIEW-v0.5`, `NOTIFICATION-COPY-CATALOG-v0.4` S02/S05/S09.
- `BE-IMPLEMENTATION-PLAN-2026-10-01` §1·§4.6·§5, `DEVTEAM-CODE-DIFF-HIGHLIGHT-2026-09-29` S7.
