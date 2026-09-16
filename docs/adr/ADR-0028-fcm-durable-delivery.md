# ADR-0028: 고정 수신자 FCM outbox와 트랜잭션 밖 발송

| 항목 | 값 |
| --- | --- |
| 상태 | Accepted |
| 날짜 | 2026-09-14 |
| 관련 | [LLD-0036](../lld/LLD-0036-fcm-durable-delivery.md), #608, #604 |

## 맥락 (Context)

업무 트랜잭션에서 FCM HTTP를 호출하면 외부 지연 동안 DB 연결과 잠금을 점유한다. 커밋 후 리스너에서 처음 요청을 저장하면 커밋과 요청 저장 사이 프로세스 종료로 알림이 유실된다. 기존 이력은 토큰의 현재 소유자로 조회하므로 계정 전환 뒤 과거 이력의 수신자가 바뀔 위험도 있다.

## 결정 (Decision)

업무 트랜잭션에서 고정 수신자와 기기별 발송 내용을 outbox에 저장한다. 커밋 후 최초 시도를 즉시 깨우고 주기적인 worker가 실패·중단된 요청을 회수한다. claim과 finalize는 짧은 별도 DB 트랜잭션으로 수행한다. 인증 토큰 발급 및 FCM HTTP는 트랜잭션 밖에서 시간제한을 두고 수행한다.

lease와 fence로 worker 소유권을 구분하고 이전 시도의 finalize를 차단한다. 발송 직전에 회원 ACTIVE, 토큰 활성 및 고정 수신자 일치, 알림 설정, 필요한 가족 관계를 재검증한다. 신규 이력에는 고정 수신자 FK를 기록한다.

credential refresh 뒤 HTTP 직전 별도 preflight 트랜잭션에서 소유권·만료·자격을 다시 조회한다. 영구 무효 토큰은 ID·owner·토큰 문자열 조건의 bulk UPDATE로 비활성화해 동시 계정 전환을 덮어쓰지 않는다. 심박 긴급 이벤트도 persistence 업무 트랜잭션에서 발행해 기록과 outbox를 원자적으로 저장한다.

일반 하트와 긴급 심박이 모두 `HEART_MESSAGE`를 사용하므로 긴급 여부를 별도로 저장한다. 운영 기본값은 2026-09-14 사용자 승인에 따라 추가 재시도 5회, 일반 24시간·긴급 5분이다. 관리자 테스트는 제한시간이 있는 동기 발송으로 기존 HTTP 성공 토큰 수 응답을 유지한다.

후속 검수에서 REQUIRED enqueue가 읽기 전용 스케줄러 트랜잭션에 참여하는 결함을 발견했다. 걷기·건강 일정 스케줄러를 쓰기 트랜잭션으로 수정한다. 모든 호출자의 외부 tx·self-invocation·동기 이벤트 경계를 조사하고 업무와 outbox의 원자성을 유지한다. 일괄 REQUIRES_NEW는 업무 rollback에도 알림이 남으므로 선택하지 않는다. 기존 앨범 조회 기록의 독립 트랜잭션은 유지한다.

서버 재시도 기한만으로 FCM 수락 이후 플랫폼 보관을 제한할 수 없다. 따라서 OAuth 갱신과 preflight 이후 원래 outbox 만료까지 남은 시간을 Android ttl(초 내림, 최대 28일)에 전달하고, APNs apns-expiration에는 원래 만료의 epoch 초를 전달한다. 만료된 요청은 HTTP를 시작하지 않는다. 재시도마다 같은 outbox ID를 data.notificationId로 보내 앱 dedup 구현을 지원한다. `max-retries`는 추가 재시도 수이며 총 허용 시도는 `1 + max-retries`다. 승인 기본값을 운영 YAML과 Compose에 제공하되 환경변수 재정의는 배포 전에 확인한다. payload 형식은 [FCM 공식 문서](https://firebase.google.com/docs/cloud-messaging/customize-messages/setting-message-lifespan)를 따른다.

관리자 테스트는 기본 30초 발송 예산 안에서 다음 토큰의 HTTP 제한시간이 남을 때만 호출한다. HTTP 직전 ACTIVE·토큰 owner와 활성 상태를 재검증하고 기존 설정 우회 동작은 유지한다. `fcm.send` timer 이름은 유지하며 기기별 durable dispatch를 측정한다.

## 고려한 대안 (Considered Options)

1. **업무 트랜잭션에서 직접 HTTP** — 단순하지만 외부 지연과 장애가 업무 커밋에 영향을 준다.
2. **AFTER_COMMIT에서 enqueue** — 업무 실패를 격리하지만 커밋 후 저장 전 유실 구간이 남는다.
3. **업무 트랜잭션 outbox** — 업무와 요청을 원자적으로 저장하고 재시작 후 회수한다. 추가 테이블과 worker 운영이 필요하다.
4. **별도 브로커** — 소비자 확장에 유리하지만 DB와 브로커 간 원자성 문제를 별도로 해결해야 한다.

## 결과 (Consequences)

### 긍정

- 업무 rollback이면 outbox도 사라져 발송되지 않는다.
- 토큰 소유자가 바뀌어도 발송 수신자를 다른 회원으로 바꾸지 않는다.
- 외부 실패를 영속 상태로 관찰하고 제한된 횟수와 유효기간 내에서 회수한다.

### 부정 / 트레이드오프

- FCM 수락 후 finalize 전 프로세스 종료 시 중복 전달될 수 있다. exactly-once를 보장하지 않는다.
- 발송 직전 검증 뒤 HTTP 사이에 회원·관계 상태가 변경되는 경쟁 구간은 남는다.
- HTTP 성공은 단말 수신·화면 노출 보장이 아니다.
- 플랫폼 보관 기한과 서버 재시도 기한은 다르다. Android TTL은 상대 기간이므로 제공자까지의 전송 지연이 남고, APNs 절대 만료 설정도 단말 도착 시각을 보장하지 않는다.
- 이미 송신 중인 HTTP를 회수할 수 없다. notificationId를 전달해도 기존 앱 dedup은 미구현이므로 중복 제거를 보장하지 않는다.

## 후속 / 미결정

- 2026-09-14 사용자 승인: 최초 즉시 시도와 최대 5회 추가 재시도, 일반 발생 후 24시간·긴급 5분. 기한이 지난 긴급 요청은 EXPIRED로 보존한다.
- 2026-09-14 사용자 승인: 원수신자 불명 이력은 DB에 보관하고 앱 목록·개수·읽음 처리에서 숨긴다. 현재 token owner로 backfill하거나 삭제하지 않는다.
- 운영 FCM·PG·AWS 호출과 운영 MySQL 마이그레이션 검증은 수행하지 않는다.
