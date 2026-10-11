# LLD-0064: 방장 변경 알림과 보호자 정렬 순서

> Low-Level Design. 이 문서는 이슈 #698 구현과 PR 검수의 기준이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #698 |
| 관련 ADR | ADR-0037, ADR-0038 결정 8 |
| 작성자 | Codex / feature-698 |
| 작성일 | 2026-10-01 |

## 1. 목적 / 배경

현재 방장 변경은 `isLeader`만 바꾸므로 새 방장이 지정 사실을 알 수 없다(E21). 보호자 목록에는 저장된 순서가 없어 이후 자동전화가 방장 다음에 누구에게 연락할지 결정하거나, 통화 중 바뀐 순서를 재계산할 수 없다(E23). 방장 변경 때 R01을 새 방장 한 명에게 기록·발송하고, 방장이 정한 보호자 순서와 revision을 가족 단위로 저장한다.

## 2. 범위

### In scope

- `widyu-domain`: `FamilyMembership.sortOrder`, `Family.familyOrderRevision`과 값 변경 메서드.
- `widyu-api`: `GuardianMyPageService.changeLeader`의 R01 이벤트 발행, 동기 리스너의 enqueue, 방장 전용 보호자 순서 변경 API, `FamilyMemberListResponse`의 순서·revision 반영, Repository·Swagger·요청 DTO, 가입·삭제 때 순서/revision의 최소 갱신.
- `scripts/mysql/alter_family_membership_sort_order.sql`: 두 컬럼 추가와 기존 보호자의 `connected_at, id` 기준 가족별 순서 백필. 구현 때 `docs/erd/ERD-0001-initial-domain.md`도 갱신한다.

### Out of scope

- 자동전화 제공자·콜백·상태기계·발신 실행(ADR-0038 결정 8, T3 회신 뒤 별도 LLD). 이 작업은 그 순서와 revision만 제공한다.
- 보호자별 안전 알림 수신 대상 편집, 다른 보호자의 푸시 설정 조회·변경, R01 이외 가족관리 알림, 대표 비상연락자(`isRepresentative`) 정책 변경.
- `FcmCategory` 값 추가, 알림센터 저장 모델 재설계. R01은 선행 W2의 `NotificationType.FAMILY_LEADER_CHANGED`와 W3의 수신자당 센터 저장 계약을 사용한다.

## 3. 인터페이스 / API

기존 `PATCH /api/v1/mypage/guardian/family/members/{memberId}/leader`는 성공 응답(`MYPAGE_2020`, `data: null`)을 유지한다. `memberId`는 같은 가족의 **다른 보호자**여야 한다. 성공하면 새 방장 한 명에게 R01이 생성된다.

새 API는 `PATCH /api/v1/mypage/guardian/family/members/order`다. 인증된 현재 가족 방장만 호출한다. `guardianIds`는 **현재 가족에 속한 보호자 전원**의 회원 ID를 위에서 아래 순서로 담으며, 방장 자신도 정확히 한 번 포함한다. 시니어 ID, 다른 가족 ID, null, 빈 목록, 누락, 중복은 400이다. 길이와 집합을 현재 가족 보호자 목록과 비교한 뒤 순서대로 `sortOrder=0..N-1`을 저장한다. 같은 순서를 재전송해도 성공 요청마다 revision을 정확히 1 올린다.

```http
PATCH /api/v1/mypage/guardian/family/members/order
Content-Type: application/json

{"guardianIds":[31,24,27]}
```

```json
{"code":"MYPAGE_2029","message":"보호자 순서 변경 성공","data":{"familyOrderRevision":4},"traceId":null}
```

기존 `GET /api/v1/mypage/guardian/family/members`는 `members`의 시니어 항목을 현재처럼 먼저 두고, **보호자 항목만** `sortOrder ASC, connectedAt ASC, membership.id ASC`로 내보낸다. 응답 최상위 `familyOrderRevision`을 추가한다. `sortOrder` 자체는 응답에 노출하지 않으며 배열 위치가 화면 순서다. 기존 항목 필드와 `MYPAGE_2019` 코드는 유지한다.

```json
{"code":"MYPAGE_2019","message":"가족 멤버 목록 조회 성공","data":{"isCurrentUserLeader":true,"familyOrderRevision":4,"members":[{"memberId":10,"name":"시니어","profileImage":null,"isLeader":false,"isCurrent":false,"isSenior":true},{"memberId":31,"name":"보호자","profileImage":null,"isLeader":true,"isCurrent":true,"isSenior":false}]},"traceId":null}
```

요청 DTO `GuardianOrderUpdateRequest`는 `widyu-api/mypage/dto/request`, 응답 DTO는 같은 모듈의 `dto/response`에 `from()`/`of()` 팩토리로 둔다. Controller는 CommandService만 호출하고 Repository를 직접 참조하지 않는다. `GuardianMyPageDocs`에 새 API와 400/403을 문서화한다.

## 4. 데이터 모델

| 테이블 | 컬럼 | 정의 | 이유 |
| --- | --- | --- | --- |
| `family_membership` | `sort_order` | `INT NOT NULL`, 가족별 0부터 시작 | 보호자 화면·후속 자동전화의 순서 |
| `family` | `family_order_revision` | `BIGINT NOT NULL DEFAULT 0` | 가족 전체 순서 스냅샷 버전 |

revision은 개별 보호자 행이 아니라 **가족 한 곳의 정렬 상태**를 나타내므로 `Family`에 둔다. 각 `FamilyMembership`에 두면 여러 행 갱신 중 어느 값이 정본인지 불분명해진다. JPA `Family` 행의 `PESSIMISTIC_WRITE` 잠금은 순서 PATCH와 방장 변경 두 경로에 적용한다. PATCH 성공 시 revision을 한 번 증가시킨다. 새 보호자 가입은 `max(sortOrder)+1`을 부여하고 revision을 1 올린다. 보호자 삭제는 revision만 1 올리고 순서 간격을 허용한다. 가입·삭제에 새 가족 행 잠금이나 재번호 매기기를 추가하지 않는다. 최초 가족/방장 생성은 `sortOrder=0`, revision `0`이다. 방장 변경만으로는 순서가 바뀌지 않아 revision을 올리지 않는다.

마이그레이션 스크립트는 `sort_order`를 먼저 nullable로 추가하고, MySQL 8의 `ROW_NUMBER() OVER (PARTITION BY family_id ORDER BY connected_at, id) - 1` 결과를 임시 테이블로 만들어 ID별 백필한 다음 `NOT NULL`로 바꾼다. `connected_at`이 같은 행은 membership ID로 결정적으로 정렬한다. `family_order_revision`은 기존 가족에 0을 준다. 운영이 `ddl-auto=validate`이므로 앱 배포 전에 SQL을 적용한다. `sort_order`는 enum이 아니며 `FcmCategory`의 native ENUM 변경은 없다. H2 테스트는 MySQL 백필 SQL 검증을 대체하지 않는다.

## 5. 처리 흐름

`findFamilyIdByGuardianId` 등 첫 일반 SELECT가 REPEATABLE READ 스냅샷을 만들 수 있으므로, 방장 변경과 정렬 PATCH는 가족 행을 잠근 뒤 보호자 전원 목록을 JOIN FETCH 없는 `findAllByFamilyIdForUpdate` 잠금 조회로 읽어 최신 커밋 행을 기준으로 검증한다.

### 방장 변경(E21)

1. 기존 CommandService의 방장 권한 확인에 더해 `GuardianMyPageService.changeLeader`의 트랜잭션 안에서 가족 행을 잠그고, 잠금 **이후 DB 조회**로 호출자가 현재 방장인지 다시 확인한다. 잠금 전 로드한 membership의 `isLeader` 값만 다시 읽으면 1차 캐시 때문에 이전 권한으로 통과할 수 있다. 대상이 같은 가족의 다른 보호자인지 확인한다. 본인 대상은 실제 변경이 아니므로 400으로 거부한다.
2. 같은 트랜잭션에서 기존 방장을 해제하고 대상 한 명을 방장으로 지정한다. `isRepresentative`와 보호자 순서는 그대로 둔다.
3. `FamilyLeaderChangedEvent`(새 방장 회원 ID)를 발행한다. 별도 `@EventListener` 빈은 동기적으로 `NotificationCopy.of(FAMILY_LEADER_CHANGED, "R01", Map.of())`와 `FcmSendDto`를 만들어 `FcmOutboxService.enqueue(newLeaderId, dto)`를 **같은 트랜잭션**에서 한 번 호출한다. `@Async`를 사용하지 않는다. DB 저장이 실패하면 방장 변경도 롤백한다. 외부 FCM 전송은 기존 outbox dispatcher가 커밋 뒤 처리한다.
4. type은 `FAMILY_LEADER_CHANGED`, 문구는 `이제 가족 방장이 되었어요.` / `가족 관리와 중요한 알림을 확인해주세요.`, 딥링크는 W2의 `widyu-care://family/manage`를 쓴다. `PUSH_AND_CENTER`, `GENERAL`, `ROUTINE_90D` 정책을 따른다. 센터 필터는 없다(전체·안 읽음에서 노출). 구 방장과 다른 보호자에게는 event/enqueue가 없다. W3의 센터 저장이 선행돼 토큰 0개나 푸시 OFF라도 새 방장 센터 행 1건이 남는다.

### 정렬(E23)

1. PATCH 트랜잭션에서 가족 행을 잠근 뒤 DB 조회로 현재 호출자의 소속·방장 상태를 재검증한다. `FamilyMembershipRepository`가 같은 가족의 보호자 전원을 읽는다. 잠금 전 CommandService 검사만으로 인가를 끝내지 않는다.
2. 요청 길이, null·중복 여부, 실제 보호자 ID 집합 일치를 검사한다. 하나라도 다르면 400으로 끝내며 순서와 revision은 바꾸지 않는다.
3. 요청 배열 위치대로 `sortOrder`를 저장하고 `Family.incrementOrderRevision()`을 한 번 호출한다. 응답에는 증가한 revision을 준다. 조회는 해당 가족의 membership을 `sort_order, connected_at, id` 순서로 읽고 같은 읽기 트랜잭션에서 revision과 함께 응답을 만든다.
4. 이후 자동전화 구현은 `family_order_revision`이 달라졌을 때 이미 시도한 보호자를 제외하고 최신 위→아래 순서를 다시 조회한다. 매 발신 전에 현 방장·사건·관계·수신대상·번호·정책/순서 revision을 재검증하고, 방장 이전 뒤 구 방장 예약을 취소한다(ADR-0038 결정 8, B §6). 이 작업은 발신을 구현하지 않는다.

## 6. 예외 / 에러 처리

| 조건 | 응답 | 상태 변화 |
| --- | --- | --- |
| 비방장 또는 가족 밖 호출자 | 403 `AUTH_4030` | 없음 |
| PATCH `guardianIds` 누락/null/빈 목록/중복/가족 구성원 누락/가족 밖 또는 시니어 ID | 400 `BAD_REQUEST` | 순서·revision 불변 |
| 방장 변경 대상이 없는 회원 또는 같은 가족이 아님 | 기존 404 `MEMBER_NOT_FOUND` | 방장·R01 불변 |
| 방장이 자신을 새 방장으로 지정 | 400 `BAD_REQUEST` | 방장·R01 불변 |
| R01 enqueue DB 실패 | 기존 서버 오류, 트랜잭션 롤백 | 방장 변경·센터/outbox 모두 롤백 |

푸시 provider 실패는 기존 outbox 재시도 정책을 따르며 이미 커밋된 방장 변경을 되돌리지 않는다. API 재요청으로 이미 방장인 본인을 지정하면 400이고 R01을 추가하지 않는다. 가족 잠금은 동시 방장 이전과 정렬 시 권한·대상 집합의 오래된 판정을 막는다.
첫 일반 SELECT에서 생긴 REPEATABLE READ 스냅샷은 가족 행 잠금 뒤에도 갱신되지 않으므로, 보호자 목록을 잠금 조회(current read)로 읽어 가입·삭제 후 오래된 목록을 기준으로 한 순열 승인과 방장 대상 판정을 막는다.
운영 OSIV에서 선행 권한 조회가 적재한 membership은 잠금 조회만으로 새 상태로 교체되지 않으므로, 두 명령은 가족 ID를 구한 직후 `EntityManager.clear()`로 영속성 컨텍스트를 비우고 첫 쓰기 전 잠금 조회에서 방장·목록을 다시 적재한다.
선행 조회를 exists 쿼리로 교체하면 다른 선적재 경로에 취약하고 각 인스턴스의 `refresh(x, PESSIMISTIC_WRITE)`는 N회 추가 조회가 필요해 채택하지 않는다.

## 7. 인수조건 (Acceptance Criteria)

- [ ] E21: 방장 변경 성공 시 새 방장에게 `FAMILY_LEADER_CHANGED` R01 enqueue가 정확히 1회 발생하고 수신자·type·문구·가족 관리 딥링크가 정본과 일치한다. 구 방장·타 보호자에게는 enqueue 0회다. 토큰 0개 또는 `GENERAL` 푸시 OFF인 새 방장의 센터 행 1건은 **W3(#696) 결합 뒤 검증**한다.
- [ ] E21: 가족 밖/없는 대상 및 본인 대상으로 방장을 바꿀 수 없고 R01이 생기지 않는다. 동시 방장 변경에서도 현재 방장만 성공한다.
- [ ] E23: 방장 PATCH는 가족 보호자 전원의 정확한 순열만 받는다. 누락·중복·추가·시니어 ID·빈/null 요청은 400이며 DB와 revision이 바뀌지 않는다.
- [ ] E23: 비방장 PATCH는 403이다. 성공한 PATCH는 재요청 포함 매번 revision을 정확히 1 올리고, GET의 보호자 배열이 지정 순서를 따른다. 시니어 항목 위치와 기존 필드는 유지된다.
- [ ] E23: 기존 행은 가족별 `connected_at, id` 순서로 0부터 백필된다. 새 보호자 가입은 `max(sortOrder)+1`과 revision+1, 보호자 삭제는 revision+1을 적용하며 순서 간격을 허용한다. 가족 행 잠금 아래 동시 정렬·방장 이전은 구 방장 쓰기를 허용하지 않는다. 두 명령은 잠금 뒤 최신 커밋 membership 목록으로 대상과 전체 순열을 검증한다.
- [ ] E23/B §6: 자동전화 구현이 사용할 `sortOrder`·`familyOrderRevision`을 조회할 수 있고, 진행 중 정렬 변경은 시도하지 않은 보호자를 최신 순서로 재계산할 수 있다(발신 로직 자체는 범위 밖).
- [ ] Swagger에 PATCH 성공·400·403과 GET 추가 필드를 반영한다. JUnit 5·Mockito(BDDMockito `given/willReturn`) 테스트는 한글 언더스코어 이름, `<행위>하면 <결과>한다` DisplayName, 상태 검증 우선으로 작성한다. 이벤트 enqueue 같은 부수효과는 `verify`로 확인한다.
- [ ] `./gradlew compileJava`, `bash scripts/harness/run-module-tests.sh domain`, `bash scripts/harness/verify.sh`가 통과한다. MySQL DDL은 H2와 별도로 검토한다.

## 8. 영향 범위 / 마이그레이션

`GuardianMyPageService`, Command/QueryService, Controller/Docs, `FamilyMemberListResponse`, 가족 Repository와 가입·탈퇴 경로가 영향받는다. `isRepresentative`는 이 PR에서 바꾸지 않는다. 새 컬럼과 `FamilyMemberListResponse.familyOrderRevision`은 기존 클라이언트 응답에 추가되는 값이다. 운영 DB에는 `scripts/mysql/alter_family_membership_sort_order.sql`을 선적용한다. 해당 스크립트는 `family_membership.sort_order` 백필과 `family.family_order_revision` 초기화를 모두 포함한다. 엔티티 변경 후 Q 클래스 재생성(`./gradlew compileJava`)과 ERD 갱신이 필요하다. W3(#696) 결합 전에는 토큰 0개·푸시 OFF인 R01의 센터 행 조건을 검증할 수 없으므로, 이번 테스트는 enqueue의 수신자·type·data를 확인한다.
운영 프로필은 `spring.jpa.open-in-view`를 명시적으로 끄지 않아 기본값 `true`라는 전제이며, 앞단 권한 조회와 서비스 트랜잭션이 같은 영속성 컨텍스트를 사용할 수 있다.

## 9. 미결정 사항 (Open Questions)

- 계획 §5 T3: **「자동전화 제공자·콜백·푸시 OFF 비방장 | 미정 → 구현 보류 | — | W12 전면」**. 이 LLD는 순서·revision만 저장한다.
- 계획 §5 T1-④: **「`initial_alert_sent_at_ms` = 스케줄러 enqueue 시각(ADR-0035 결정 3과 같은 근사. provider 수락 시각이 필요하면 `finish` 첫 성공에서 덮는 후속) | 그대로 | W12」**. 이 LLD는 시각을 계산하지 않는다.
- B §6: 방장 이전 중 새 방장의 call stage는 기술 합의가 남았다. **구현 가정: 호출 상태기계 보류**, 구 방장 예약 취소·현 방장 재검증 요구만 후속 자동전화 LLD에 전달한다.
- FE-DELIVERY §12의 「대표는 현 방장에서 파생」과 현행 `SeniorMyPageService.updateRepresentativeContact`(시니어 독립 지정)의 충돌은 제품 확인 뒤 별도 작업으로 다룬다. 이 PR은 `isRepresentative`를 변경하지 않는다.

## 10. 참고

- ADR-0037(이 worktree에 없으면 `feature-691/docs/adr/ADR-0037-notification-center-model.md`), ADR-0038 결정 8.
- 알림센터 승인판 `FE-HANDOFF-v0.4.md` §1-17·§4-21·§5.3, `NOTIFICATION-COPY-CATALOG-v0.4.md` §8 R01, `HEMLO-REVIEW-v0.5.md` §6·§8, `B-SELFCHECK-ORDER-SPEC-v0.1.md` §6.
- `BE-IMPLEMENTATION-PLAN-2026-10-01.md` §1·§4.6·§5, `DEVTEAM-CODE-DIFF-HIGHLIGHT-2026-09-29.md` E21·E23.
