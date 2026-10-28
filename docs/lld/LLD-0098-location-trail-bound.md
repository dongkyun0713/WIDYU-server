# LLD-0098: 이동 경로 Redis 리스트 상한

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #835 |
| 관련 ADR | - |
| 작성자 | Claude / feature-835 |
| 작성일 | 2026-10-22 |

## 1. 목적 / 배경

이동 경로 리스트 `location:trail:{memberId}`에 상한이 없다. `RealtimeLocationService.updateAndBroadcast`는 위치마다 `LPUSH` 뒤 `EXPIRE 900s`를 다시 걸고 `LTRIM`을 하지 않는다. 앱은 정지 중에도 60초마다 keepalive를 보내므로(LLD-0048) 키가 만료되지 않고, 하루 1,440점(60초 정지)~약 1.7만 점(5초 이동)이 쌓인다. 조회는 `range(0, -1)`로 전체를 읽는다.

운영 Redis는 `maxmemory 256mb`·`noeviction`이라 가득 차면 토큰·인증번호·위치를 포함한 모든 쓰기가 실패한다.

## 2. 범위

### In scope

- 변경 모듈: widyu-api
- `LPUSH` 직후 `LTRIM 0 899`로 최근 900점만 남긴다.
- 이동 경로 조회를 `range(0, 899)`로 같은 상한까지만 읽는다.
- 위치마다 TTL 900초를 다시 거는 동작은 유지한다.

### Out of scope

- 조회 결과를 시각 기준 최근 15분으로 거르는 변경(9절).
- 이동 경로 `LinkedHashMap` 수동 변환 정리(LLD-0097 2절).
- `LPUSH`·`LTRIM`·`EXPIRE`를 한 번의 왕복(파이프라인·Lua)으로 묶는 최적화.

## 3. 인터페이스 / API

API 계약 변경 없음. `GET /api/location/seniors/{memberId}/trail`은 최대 900점을 오래된 순으로 돌려준다.

## 4. 데이터 모델

키·타입·TTL 변경 없음. 리스트 길이 상한 900이 생긴다.

상한 900은 초당 1건으로 보내도 15분(900초)을 담는 값이다. 앱의 실제 이동 중 전송 간격은 5초(LLD-0048)라 15분 이동은 180점이다. 점 하나가 JSON 약 90바이트이므로 시니어당 최대 약 80KB다.

## 5. 처리 흐름

1. `updateAndBroadcast`: `LPUSH trail point` → `LTRIM trail 0 899` → `EXPIRE trail 900`.
2. `getLocationTrail`: `LRANGE trail 0 899` → 변환 → 오래된 순으로 뒤집어 반환.

배포 전에 이미 길어진 키는 다음 위치 갱신의 `LTRIM`에서 잘린다. 그 사이 조회도 900점까지만 읽는다.

## 6. 예외 / 에러 처리

새 에러 코드 없음. `LTRIM` 실패는 다른 Redis 명령과 같이 위치 갱신 트랜잭션으로 전파된다.

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1. 위치를 갱신하면 `location:trail:{memberId}`를 `0..899`로 자르고 TTL 900초를 다시 건다.
- [x] AC2. 이동 경로 조회는 `0..899`만 읽고 오래된 순으로 돌려준다.
- [x] AC3. 변경한 테스트 클래스와 `compileJava`가 통과한다.

## 8. 영향 범위 / 마이그레이션

- 배포 후 첫 위치 갱신에서 기존의 긴 리스트가 900점으로 줄어든다. 되돌릴 수 없지만 이동 경로는 임시 데이터다.
- 지금까지 15분보다 긴 경로를 받던 화면은 최근 900점까지만 받는다.
- 마이그레이션·설정 변경 없음.

## 9. 미결정 사항 (Open Questions)

- [ ] 이동 경로를 「최근 15분」(LLD-0001)으로 시각 기준 거를지. 위치마다 TTL을 다시 걸기 때문에 지금은 위치가 끊기지 않는 한 15분보다 긴 경로가 돌아온다. 상한을 둔 뒤에도 정지 중 60초 keepalive만 오면 900점이 최대 15시간을 덮는다. 거르면 앱 화면에 보이는 경로 길이가 바뀌므로 앱 요구를 확인해야 한다.

## 10. 참고

- Issue #835
- LLD-0001, LLD-0048, LLD-0097
- `docker-compose.prod.yml`의 Redis `maxmemory`·`maxmemory-policy`
