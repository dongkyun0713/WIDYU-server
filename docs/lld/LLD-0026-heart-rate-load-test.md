# LLD-0026: 심박 WebSocket-AI 부하 테스트

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #546 |
| 관련 ADR | ADR-0022 |
| 작성자 | Codex |
| 작성일 | 2026-09-08 |

## 1. 목적 / 배경

심박 WebSocket SEND부터 ACK까지와 AI·저장·긴급 알림·FCM 단계의 지연을 같은 부하 회차에서 수집해
병목을 추측이 아니라 p95·p99와 자원 포화 근거로 판정한다.

## 2. 범위

### In scope

- 변경 모듈: widyu-api, k6 부하 테스트, scripts/k6, docs/adr, docs/lld
- SockJS/STOMP 연결·SEND·ACK 상관관계를 처리하는 k6 시나리오
- AI API 직접 호출 시나리오
- 단건·배치, 정상·긴급, 실제 AI·stub 비교가 가능한 실행 변수
- 실행 환경, k6 원본 summary, Actuator, 컨테이너 CPU·메모리 표본 저장
- WebSocket, AI, 저장, 긴급 알림, FCM의 Micrometer 타이머와 p95·p99 설정

### Out of scope

- 측정 전 비동기 큐, 메시지 브로커, Reactive HTTP 또는 AI batch 도입
- 기존 WebSocket·AI·저장·FCM 계약 변경
- 운영 계정이나 실제 FCM 대상 사용
- 제품 SLO 임의 결정

## 3. 입력과 실행 계약

시니어별 `memberId`, JWT를 담은 CSV를 외부 파일로 받는다. JWT와 실제 사용자 정보는 커밋하지 않는다.

```csv
member_id,access_token
900001,<access-token>
```

WebSocket 시나리오는 `/ws/location/{server}/{session}/websocket` SockJS transport로 연결하고 STOMP
`/user/queue/heart-rate/result`, `/user/queue/errors`를 구독한다.

- 단건: `/app/heart-rate/send-single`, 1초마다 1건
- 배치: `/app/heart-rate/send`, 15초마다 15건
- 정상: 기본 70bpm
- 긴급: 기본 180bpm
- 상관키: 요청과 응답의 `measuredAt`

측정 종료 후 기본 15초 동안 신규 전송 없이 ACK를 기다린 뒤 연결을 닫아 종료 직전 요청을 유실로
잘못 집계하지 않는다.

AI 직접 시나리오는 같은 사용자 ID와 유입 주기로 `{AI_SERVER_URL}/api/hr`을 호출한다.

## 4. 출력

각 회차 디렉터리에 다음 원본을 남긴다.

- `summary.json`: k6 전체 summary
- `k6.log`: 실행 로그
- `actuator.prom`: 타임스탬프가 포함된 Prometheus 표본
- `stub-metrics.jsonl`: AI·FCM stub 요청 수 표본(stub 실행 시)
- `docker-stats.tsv`: API·AI·DB·Redis 컨테이너 CPU·메모리 표본
- `environment.json`: Git SHA, OS, CPU·메모리, k6·Docker 버전, 이미지와 DB·Redis 버전

각 회차 전에 API와 AI를 재시작해 Micrometer percentile과 AI의 사용자별 상태를 초기화한다.
집계기는 같은 조건의 3회 중 p99 중앙값을 대표값으로 사용한다. 오류나 timeout이 처음 발생하거나
직전 부하 대비 p99가 두 배 이상 증가한 단계를 포화 후보로 표시한다. 최종 포화 판정은 후보 지점에서
executor queue, DB pending, CPU 중 하나가 함께 증가했는지 원본 표본으로 확인한다.

## 5. 메트릭

### k6

- `heart_ws_ack_duration`: SEND부터 ACK까지 지연
- `heart_ws_sent`, `heart_ws_acked`: WebSocket 메시지 전송·ACK 처리량
- `heart_measurements_sent`, `heart_measurements_acked`: 단건·배치의 측정값 처리량
- `heart_ws_error`: 오류율
- `heart_ws_lost`, `heart_ws_duplicate`, `heart_ws_out_of_order`: 무결성
- `heart_ai_direct_duration`, `heart_ai_direct_error`: AI 직접 지연·오류율

### Actuator / Prometheus

- `heart.websocket.processing`: WebSocket inbound 처리
- `heart.ai.detection`: AI 호출을 포함한 판정
- `heart.ai.request`: 개별 AI HTTP 요청(`outcome=success|timeout|error`)
- `heart.persistence`: Redis·JPA 저장
- `heart.emergency.notification`: 동기 긴급 알림 리스너
- `fcm.send`: 보호자 한 명의 FCM 처리
- `executor.active`, `executor.queued`: WebSocket inbound executor
- `hikaricp.connections.active`, `hikaricp.connections.pending`: DB pool
- `jvm.gc.pause`, JVM 메모리와 process CPU

`heart.ai.request`의 outcome 태그와 k6 오류 지표로 AI timeout 및 WebSocket 오류를 집계한다.

## 6. 안전 조건

- 긴급 시나리오는 실제 사용자 데이터에서 실행하지 않는다.
- API의 `FIREBASE_MESSAGING_URL`은 stub으로 고정하고 별도 테스트 Firebase 자격증명만 사용한다.
- 실제 AI 비교에서도 DB·Redis는 테스트 전용 인스턴스를 사용한다.
- 사용자 CSV와 환경 파일의 시크릿은 커밋하지 않는다.

## 7. 인수조건 (Acceptance Criteria)

- [x] 단건·배치의 SEND부터 ACK까지 p95·p99를 수집할 수 있다.
- [x] 정상·긴급 입력을 분리할 수 있다.
- [x] 실제 AI와 stub을 같은 유입량으로 비교할 수 있다.
- [x] AI 직접 호출과 WebSocket end-to-end 결과를 비교할 수 있다.
- [x] 전송·ACK 수, 오류, 유실, 중복, 사용자별 순서 역전을 기록한다.
- [x] 단계별 p95·p99와 executor·DB·JVM·컨테이너 자원을 보존한다.
- [x] 60초 워밍업, 5분 측정, 사용자 1·10·25·50·100, 3회 반복이 기본값이다.
- [ ] 운영 동등 환경과 stub 환경에서 전체 매트릭스를 실행하고 원본 결과를 커밋한다.
- [ ] 운영 예상 규모와 ACK·위급 알림 SLO를 기록한다.
- [ ] 결과 기반 개선안 ADR과 후속 구현 이슈를 생성한다.
- [x] `bash scripts/harness/run-module-tests.sh`가 통과한다.

## 8. 영향 범위 / 마이그레이션

- API·WebSocket·AI 계약과 데이터 모델 변경 없음.
- DB/Redis 마이그레이션과 MySQL ENUM 변경 없음.
- 부하 테스트용 JWT 목록과 결과 파일만 추가로 필요하다.

## 9. 미결정 사항 (Open Questions)

- 운영 기준 예상 동시 시니어 수와 향후 1년 목표 규모
- 정상 ACK와 위급 알림의 p95·p99 SLO
- 운영과 동등한 AI·DB 사양에서 테스트할 수 있는 환경

## 10. 참고

- Issue #546
- ADR-0022
- LLD-0010, LLD-0019, LLD-0023
