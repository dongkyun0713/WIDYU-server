# ADR-0038: 안전 흐름 — 본인확인이 먼저이고, 보호자 알림은 사건이 보낸다

> Architecture Decision Record. 하나의 중요한 의사결정과 그 이유를 기록한다.

| 항목 | 값 |
| --- | --- |
| 상태 | Accepted (2026-10-01 사용자 승인; 2026-10-02 결정 3·6 문구를 LLD-0070~0072 구현에 맞게 개정) |
| 날짜 | 2026-10-01 |
| 관련 | #690(로드맵), #691, ADR-0035(결정 3의 의미·5·7 대체), LLD-0054(개정 대상), LLD-0011·LLD-0058(안심구역 중복 차단 대체), ADR-0037, 본인확인 명세 `B-SELFCHECK-ORDER-SPEC-v0.1` §1~§6, `FE-HANDOFF-v0.4` §5·§6, `RECONCILIATION` §6 T1~T3, 코드 변경점 표 S1~S13 |

## 맥락 (Context)

ADR-0035는 「보호자 알림 순서는 바꾸지 않는다」(결정 7)와 「본인확인 45초」(결정 5)로 사건(`incident`)을 들여왔다. 그래서 지금은 위급 판정이 저장되는 트랜잭션 안에서 동기 리스너(`HeartRateEmergencyNotificationService`)가 가족 전원에게 긴급 FCM을 넣고, 커밋 뒤에야 연구 회차 판정이 있을 때만 사건이 열려 시니어에게 확인 푸시가 간다. 제품용 단건 심박 경로는 판정 기록이 없어 사건을 열지 않고, 안심구역 이탈은 Redis 30분 키로 중복만 막은 채 바로 가족 전원에게 푸시한다. 1분 무응답 스케줄러는 상태만 `ESCALATED`로 바꾸고 아무에게도 알리지 않는다. 시니어가 「괜찮다」고 답해도 이미 나간 보호자 알림을 설명할 길이 없다.

2026-09-28 결정(28·30·31차)과 본인확인 명세 B는 순서를 뒤집는다. 심박 위급·안심구역 이탈 모두 시니어에게 1분 확인 화면(버튼은 「취소」 하나)을 먼저 띄우고, 1분 안에 취소가 없을 때만 보호자 최초 위급 알림(`INITIAL_ALERT`)을 보낸다. 취소하면 사건을 끝내고 보호자에게 정보성 알림(S08/S09)만 일반 우선순위로 보낸다. 같은 상황이 이어지는 동안 새 긴급 푸시·새 센터 항목을 만들지 않고, 취소 뒤 재감지는 새 상황이다. 5분 자동전화는 `INITIAL_ALERT` 발송 시각부터 센다.

## 결정 (Decision)

1. **사건이 유일한 보호자 알림 발행자다.** 위급 판정(배치·단건)과 안심구역 이탈은 사건을 열고 **시니어 본인확인 푸시만** 보낸다. 보호자 `INITIAL_ALERT`는 1분 무응답 스케줄러가, 괜찮다는 안내(`OK_NOTICE`)는 응답 처리가 보낸다. `HeartRateEmergencyNotificationService`는 `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`로 바꿔 두 심박 경로 모두 여기서 사건을 열고, `HeartRateBatchService.openIncident`는 지운다. 동기 리스너 안에서 사건을 열면 실패가 rollback-only로 심박 저장 커밋을 깨뜨리므로 금지한다(「저장이 판정에 앞선다」 불변식). `FallAssessmentService`가 쓰는 `openForAlert(decision, kind)` 시그니처는 유지한다.
2. **사건 모델을 넓힌다.** `decision_id`를 NULL 허용으로 바꾼다(단건·안심구역은 판정 기록이 없다. MySQL UK는 NULL 중복을 허용한다). `kind`에 `SAFE_ZONE_EXIT`를 더한다. 본인확인 시간은 **60초**(`sensor.incident.self-check-sec` 기본값 변경). 컬럼을 더한다: `device_responded_at_ms`(원 단말 클릭 시각), `initial_alert_sent_at_ms`, `last_decision_id`·`detection_count`(같은 사건에 붙은 판정), `situation_ended_at_ms`, `guardian_response_type/at/by`(MESSAGE_SENT|CALL_INITIATED), `policy_revision`. 상태 enum 이름은 바꾸지 않고 명세 논리명과의 대응만 적는다(`CHECKING`=SELF_CHECK_PENDING, `ESCALATED`=GUARDIAN_PENDING, `OK_CLOSED`=CLOSED_OK). 판정 기록이 없는 경로의 중복 열기는 `memberRepository.findByIdForUpdate`로 회원 행을 잠근 뒤 열린 사건을 조회해 막는다.
3. **무응답 처리는 사건별 조건부 UPDATE 두 개 + 같은 트랜잭션의 enqueue다.** 스케줄러 메서드의 `@Transactional`을 떼고, `findDueIds(now, afterId, limit)`(id 키셋, 100건·최대 10페이지)로 후보를 읽어 사건마다 별도 빈 `IncidentEscalation.escalateIfDue(id, now)`(REQUIRES_NEW, 사건별 예외 격리)를 부른다. 후보 조건은 `(state IN (OPEN,CHECKING) AND respond_by_ms < now) OR (state = ESCALATED AND initial_alert_sent_at_ms IS NULL)`이다. 한 트랜잭션 안에서 UPDATE를 **둘로 나눈다**. ① 상태 전환 `UPDATE incident SET state=ESCALATED WHERE id=:id AND state IN (OPEN,CHECKING) AND respond_by_ms < :now` — 알림을 이미 보냈는지와 무관하게 무응답 사건을 `ESCALATED`로 올린다. ② 알림 게이트 `UPDATE incident SET initial_alert_sent_at_ms=:now WHERE id=:id AND initial_alert_sent_at_ms IS NULL AND state IN (OPEN,CHECKING,ESCALATED) AND (respond_by_ms < :now OR state=ESCALATED)` — 1건이면 그 트랜잭션에서 보호자 `INITIAL_ALERT`를 enqueue한다(`decisionId`를 실어 `markDeliveredIfFirst`를 유지). 둘을 나누는 이유: 결정 7의 플래그 OFF는 사건을 열 때 `INITIAL_ALERT`를 즉시 보내고 게이트를 이미 찍어 두므로, 전환과 게이트를 한 UPDATE에 묶으면 OFF에서 무응답 사건이 영원히 `CHECKING`에 남는다(2026-10-02 독립 검수가 찾은 초판의 결함). 게이트를 상태가 아니라 **`initial_alert_sent_at_ms IS NULL`** 로 두는 이유는 마감과 다음 폴링 사이에 온 늦은 OK가 상태를 `ESCALATED`로 찍어 버리면 상태 조건으로는 알림이 영영 안 나가기 때문이다. 게이트의 `state IN (…)`은 마감 전에 취소돼 `OK_CLOSED`가 된 사건(`findDueIds`와 UPDATE 사이에 취소가 커밋된 경우 포함)과 `RESOLVED` 사건을 제외한다. 활성 보호자가 0명이면 예외를 던지지 않고 게이트만 기록하고 WARN을 남긴다(사건과 본인확인 푸시를 롤백하지 않는다). 배포 DDL은 기존 사건의 `initial_alert_sent_at_ms`를 `opened_at_ms`로 백필해 옛 사건에 `INITIAL_ALERT`가 다시 나가지 않게 한다. 인덱스 `(initial_alert_sent_at_ms, respond_by_ms)`를 둔다. enqueue가 실패하면 두 UPDATE도 되돌아가 다음 폴링이 다시 시도한다(enqueue는 사건당 한 건). 실제 전송은 outbox의 at-least-once라 중복 전달을 막지 못한다(ADR-0028).
4. **늦은 OK는 응답만 남긴다.** 마감 뒤 OK는 지금처럼 `ESCALATED`를 유지하고 `OK_NOTICE`를 보내지 않는다. `OK_NOTICE`는 응답 처리가 조건부 UPDATE 뒤 다시 읽은 상태가 `OK_CLOSED`일 때만 같은 트랜잭션에서 enqueue하므로 enqueue는 정확히 한 번이다(전송은 at-least-once). 정확히 마감 시각에 도착한 취소는 초과로 본다(B §3 제안 수용). `responded_at_ms`는 서버 시각, 단말 시각은 `device_responded_at_ms`에 따로 둔다.
5. **`HELP` 값은 유지한다(가정, T1-②).** API는 `HELP`를 계속 받되 상태만 `ESCALATED`로 찍는다. 그러면 결정 3의 게이트가 다음 폴링(≤5초)에 `INITIAL_ALERT`를 보낸다. 즉시 발송 코드를 따로 두지 않는다. 회신이 「폐기」면 400으로 바꾼다.
6. **같은 사건은 한 번만 알린다.** 같은 회원에 `CHECKING`/`ESCALATED` 사건이 있고 `situation_ended_at_ms`가 비어 있으면, 새 위급 판정은 그 사건에 붙이고(`last_decision_id`·`detection_count`) 새 본인확인·보호자 푸시를 만들지 않는다. 상황 종료는 ① OK/RESOLVED, ② 안심구역 재진입, ③ 마지막 감지 후 `sensor.incident.situation-window-min`(기본 5분, 기존 위급 사이클과 같다) 경과다. 종료는 `situation_ended_at_ms`로만 적고 상태는 바꾸지 않는다. 재사용과 종료 판정은 상태가 아니라 `situation_ended_at_ms IS NULL`을 기준으로 한다(OK로 닫힌 사건의 중복 이탈 이벤트가 새 사건을 만들지 않도록). 안심구역 재진입 종료와 이탈 사건 열기는 회원 행 잠금 뒤에 수행하고, 이탈 리스너는 잠금 뒤 최신 체류 정보를 다시 확인해 이미 안에 있으면 사건을 열지 않는다. 판정 기록이 없는 단건 심박 경로는 `situation-window-min` 안에 연 사건만 재사용하고, 같은 판정 id의 재시도는 시간 창을 보기 전에 먼저 걸러 변경 없이 반환한다(2026-10-02 LLD-0070~0072 구현 반영). 안심구역의 Redis 30분 중복 키(LLD-0011·LLD-0058)는 **없앤다**. 사건 열림 여부가 그 역할을 대신하며, 키를 두면 OK 뒤 10분 재이탈이 새 사건이 되지 못한다.
7. **FE가 준비되기 전에는 순서를 뒤집지 않는다.** 시니어 앱에 FCM 수신·확인 화면이 없는 동안 순서를 바꾸면 보호자 알림만 1분 늦어진다. `sensor.incident.self-check-first`(기본 **false**)가 꺼져 있으면 사건을 열고 본인확인 푸시를 보내는 동시에 `INITIAL_ALERT`도 즉시 enqueue하고 `initial_alert_sent_at_ms`를 적는다(현행 체감 동작 유지). FE 배포가 확인되면 운영에서 켠다. 플래그와 무관하게 60초·`OK_NOTICE`·같은 사건 묶기는 동작한다.
8. **자동전화는 설계만 둔다.** 제공자·콜백·통화 제한시간·푸시 OFF 비방장 포함 여부(T3)가 미정이다. 상태기계(5분 → 방장 → 1분 뒤 재시도 → 정렬 순서 다음 보호자 → 연결 뒤 2분 → 전원 소진 시 `FINAL_ESCALATION` 1회·119 없음)와 재검증 규칙은 이 ADR의 후속 절에 적고, 보호자 반응 기록 API(S11)와 보호자 정렬 순서(E23)만 먼저 만든다.
9. **안전 예외(S14) 테이블은 만들지 않는다.** 임계값이 없다(S1 회신 대기). 「평가 없음 = 행 없음」으로 두어 판정 불명이 `NOT_MATCHED`로 둔갑하지 않게 한다. 스키마(`safety_exception_policy_id/version`·`rule_id`·`decision`·`evaluated_at`·evidence 지문·evaluator version)는 후속 절에 예약한다.
10. **`alert_delivered`의 뜻을 좁힌다.** ADR-0035 결정 3의 「알림 = FCM 전송 성공」은 이제 「`INITIAL_ALERT` 전송 성공」이다. 60초 안에 OK가 온 위급 판정은 `alert_delivered=false`가 **정상**이다. 내보내기 검사기의 해석도 같이 바꾼다.

## 고려한 대안 (Considered Options)

1. **두 경로 병행(즉시 보호자 푸시 + 사건)** — 현행. 「본인이 괜찮다고 해도 이미 나간 알림을 설명할 수 없다」가 그대로 남고 B §3 「단일 dispatch」에 어긋난다. 기각.
2. **동기 리스너에서 바로 사건 열기** — 코드가 가장 적지만 사건 열기 실패가 심박 저장 커밋을 되돌린다. 기각.
3. **벌크 UPDATE 뒤 별도 조회로 발송** — 지금 구조와 가깝지만 UPDATE와 발송 사이에 재시작하면 누락, 두 번 돌면 중복이다. 기각.
4. **상태 enum을 명세 이름으로 개명** — B §4가 「기존 enum을 덮어쓰지 않는다」고 했고 export 호환이 깨진다. 기각.
5. **시간 창 없이 RESOLVED까지 같은 사건으로 묶기** — 보호자가 사후 판정을 하지 않으면 영원히 묶인다. 기각.
6. **무조건 순서 전환** — FE가 없는 동안 운영 안전이 떨어진다. 플래그로 대체.
7. **자동전화 상태기계를 지금 구현(다이얼은 no-op)** — 한 구현뿐인 인터페이스와 쓰이지 않는 상태 전이가 생긴다. 보류.

## 결과 (Consequences)

### 긍정
- 보호자 알림이 한 곳(사건)에서만 나가므로 「왜 갔는지」가 사건 행 하나로 설명된다. 1분 안 OK는 보호자 긴급 푸시 enqueue 0건, 정보성 알림 enqueue 1건이 보장된다.
- 단건 심박·안심구역도 사건을 가지므로 제품 경로와 연구 경로의 동작이 같아진다.
- 늦은 OK·폴링 경계·재시작에서도 `INITIAL_ALERT`는 사건당 정확히 한 번 enqueue된다. 실제 전달은 outbox의 at-least-once 범위다(ADR-0028).

### 부정 / 트레이드오프
- 플래그를 켠 뒤에는 보호자 알림이 최대 1분 + 폴링 주기(5초)만큼 늦어진다. 이것이 제품이 선택한 트레이드오프다.
- `incident` DDL 변경(NULL 허용·컬럼·인덱스)이 필요하고, `decision_id`가 없는 사건은 `incidents.jsonl`에 실리지 않는다(run 귀속 사건만 내보낸다).
- 안심구역의 중복 차단이 Redis에서 DB 조회(열린 사건)로 바뀐다. 위치 갱신마다 사건 조회가 하나 는다.
- 연구 자료의 `alert_delivered` 해석이 바뀌어 과거 회차와 직접 비교할 수 없다. 형식서·검사기 개정은 정본 소유자 몫이다(대조표 R1).

## 후속 / 미결정
- T1 회신 대기: ① 1분 기준 시각·경계(가정: 서버 사건 생성 시각 + 60초, 정확히 마감 = 초과) ② `HELP` 유지(가정: 유지) ③ FCM 전체 실패 시 동작(가정: 본인확인 푸시가 5분 TTL 안에 못 가도 `INITIAL_ALERT`는 1분에 그대로 보낸다) ④ 5분 기준 시각(가정: 스케줄러 enqueue 시각 = `initial_alert_sent_at_ms`. provider 수락 시각이 필요하면 `finish` 첫 성공 시각으로 덮는 후속).
- T2: Incident kind ↔ 제품 이벤트는 `HR_ANOMALY↔HEART_RATE_EMERGENCY`, `SAFE_ZONE_EXIT↔SAFE_ZONE_EXITED`. 낙상은 이번 범위 밖(AI 입구 꺼짐).
- T3 자동전화 제공자·콜백·푸시 OFF 비방장 포함 여부 → 결정되면 별도 LLD.
- `self-check-first`를 켜는 시점은 FE(위듀 앱 F1·F2·F6) 배포 확인 뒤 운영 결정이다.
- 안전 예외 임계값(S1)이 정해지면 결정 9의 예약 스키마로 테이블을 만들고 OK 뒤 MATCHED 격상 층을 얹는다.
