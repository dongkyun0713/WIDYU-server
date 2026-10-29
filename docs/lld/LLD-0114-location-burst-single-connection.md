# LLD-0114: 밀린 위치가 몰려도 위치 한 건이 커넥션 하나만 쓰고, 오래된 위치는 실시간에 반영하지 않기

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #926 |
| 관련 ADR | - (원본 저장 원칙은 [LLD-0048](LLD-0048-location-fix.md)) |
| 작성자 | Claude |
| 작성일 | 2026-10-29 |

## 1. 목적 / 배경

2026-10-29 10:52·12:45(KST) 개발 서버에서 DB 커넥션 풀(10개)이 몇 초씩 고갈돼 위치 갱신, FCM 발송, 사건 타임아웃 스케줄러, HTTP 인증 확인이 함께 실패했다. 회원 30의 폰은 1~2시간 서버와 통신하지 못하다 재연결하면서 쌓아 둔 위치를 20초 안에 45건, 84건 연달아 보냈다(평소 1분에 1건). WebSocket 수신 스레드 14개가 동시에 돌았고 대기열이 98건 쌓였다.

위치 한 건은 실시간 경로 트랜잭션(`RealtimeLocationService.updateAndBroadcast(byte[])`)에서 커넥션을 쥔 채 원본 저장(`LocationFixService.store`, `REQUIRES_NEW`)으로 커넥션을 하나 더 요청했다. 동시 처리 수가 풀 크기를 넘자 풀 10개가 모두 첫 커넥션으로 잡혔고, 모두 두 번째 커넥션을 기다리다 대기 한도(3000ms)로 실패했다. Prometheus의 커넥션 최장 점유 시간이 3.1초로 대기 한도와 같았다(느린 쿼리가 아니라 서로 기다림). 원본도 10:52에 41건 중 14건, 12:45에 84건 중 35건만 남았다.

밀려 온 옛 위치는 오래된 것부터 순서대로 오므로, 그대로 실시간 경로를 타면 보호자 화면에 한 시간 전 위치부터 빨리 감기처럼 방송되고 안전구역 판정도 옛 위치로 다시 돈다. 응급 상황 이동 경로는 기획상 최근 15분만 보여 준다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api, widyu-domain(`SeniorLocation` Redis 해시 필드)
- 원본 저장을 실시간 경로 트랜잭션이 커밋된 뒤에 호출한다. 위치 한 건이 동시에 쓰는 커넥션은 하나다.
- 측정 시각(`ts_ms`) 기준 실시간 반영 조건: 15분 안에 잰 위치이면서 지금 최신 위치보다 나중에 잰 위치만 반영한다. 그 밖은 원본만 저장하고 ACK를 보낸다.
- 실제 MySQL·Redis 몰림 재현 테스트.

### Out of scope
- 위치 원본(`location_fix`) 삭제. 정책서 1.6.6에 따라 원본은 전부 남기며 보존 기간은 정해지지 않았다(LLD-0059 Out of scope와 같다).
- 커넥션 풀 크기 변경. 요청당 커넥션이 둘이면 동시 처리 수가 풀 크기를 넘을 때 다시 막히므로 구조로 고친다.
- 배치 위치 전송 API. 앱이 재연결 뒤 이전 ACK를 받고 다음 위치를 보내는 방식으로 먼저 대응한다(FE 요청).
- 최신 위치 비교의 원자성. 같은 기기의 위치가 동시에 처리되면 둘 다 같은 최신값을 읽고 통과할 수 있다. 앱이 ACK를 기다려 순서대로 보내면 기기당 동시 처리는 하나다.

## 3. 인터페이스 / API

STOMP `SEND /app/location/update`, ACK `/user/queue/location/ack`는 그대로다. 실시간에 반영하지 않은 위치의 ACK는 같은 `LocationUpdateResponse` 형식에 받은 좌표만 담는다.

```json
{ "memberId": 30, "name": null, "profileImage": null, "latitude": 37.55, "longitude": 126.97,
  "updatedAt": "2026-10-29T10:52:25", "stayStartTime": null, "stayDurationMinutes": null,
  "locationType": null, "locationName": null }
```

반영한 위치의 ACK·방송 본문은 바뀌지 않는다.

## 4. 데이터 모델

`SeniorLocation`(Redis `senior_location`, TTL 5분)에 `measuredAtMs`(Long, nullable)를 더한다. 앱이 잰 시각이며 `ts_ms`가 없는 옛 형식이면 null이다. 배포 전 해시에는 값이 없어 null로 읽히고, 그때는 비교 없이 반영한다. DB 스키마 변경 없음.

## 5. 처리 흐름

1. `updateAndBroadcast(byte[])`는 `@Transactional(propagation = NOT_SUPPORTED)` — 스스로 트랜잭션을 열지 않는다.
2. 파싱·검증(기존). v2면 `LocationFixService.validate`(기존).
3. 본인 검증(`verifySelf`). 실시간 반영 생략 여부와 상관없이 다른 회원의 위치는 `FORBIDDEN`이고 원본도 저장하지 않는다.
4. 실시간 반영 조건(`isRealtimeCandidate`)
   - `ts_ms` 없음 → 반영(옛 형식은 비교할 수 없다)
   - `지금 - ts_ms > 15분` → 생략
   - Redis 최신 위치의 `measuredAtMs`가 있고 `ts_ms <= measuredAtMs` → 생략
   - 그 밖 → 반영
5. 반영이면 `TransactionTemplate`으로 기존 `updateAndBroadcast(request)`를 자기 트랜잭션에서 실행하고 커밋한다(최신 위치 저장 때 `measuredAtMs` 기록). 생략이면 INFO 로그(memberId·seq만) 뒤 `LocationUpdateResponse.received`.
6. v2면 실시간 트랜잭션이 끝난 뒤 `LocationFixService.store`(`REQUIRES_NEW`, 기존). 이때 쥔 커넥션은 없다. 실패해도 ACK를 막지 않는다(기존).

## 6. 예외 / 에러 처리

새 에러 코드 없음. 기존과 같이 파싱·검증 실패는 `LOCATION_4000`, 다른 회원 위치는 `FORBIDDEN`, 원본 저장 실패는 WARN 뒤 ACK.

## 7. 인수조건 (Acceptance Criteria)

- [x] v2 위치는 실시간 트랜잭션을 커밋한 뒤 원본을 저장한다(단위 테스트로 커밋 → 저장 순서 확인).
- [x] 15분보다 오래 전에 잰 위치는 최신 위치 갱신·방송·이벤트 발행·실시간 트랜잭션 없이 원본만 저장하고 받은 좌표로 ACK한다.
- [x] 지금 최신 위치보다 먼저 잰 위치는 최신 위치를 덮어쓰지 않고 방송하지 않는다.
- [x] 다른 회원의 위치는 실시간 반영 생략 여부와 상관없이 `FORBIDDEN`이고 원본을 저장하지 않는다.
- [x] 풀 10개·대기 3000ms, 16개 스레드에서 한 회원 84건·회원 20명 × 40건(최근분·1시간 밀린 분)을 동시에 처리해도 실패 0건이고 원본이 모두 저장된다(`LocationBurstConnectionPoolMySqlTest`).
- [x] `bash scripts/harness/verify.sh`가 통과한다.

## 8. 영향 범위 / 마이그레이션

- 실시간 경로의 트랜잭션 경계만 옮긴다. 실시간 경로 안의 커밋 후 처리(`TransactionSynchronization` — 안전구역 이탈 재감지, 체류 정보 저장)는 `TransactionTemplate` 트랜잭션에 그대로 등록되어 같은 시점에 돈다.
- 앱 기기 시계가 15분 넘게 늦으면 그 기기의 위치는 실시간에 반영되지 않는다. 폰 시계는 망 시간으로 맞춰지는 것을 전제한다.
- DDL 없음.

## 9. 미결정 사항 (Open Questions)

없음.
