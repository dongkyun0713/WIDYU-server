# 심박 WebSocket-AI 부하 테스트 실행 기록

| 항목 | 값 |
| --- | --- |
| 관련 이슈 | #546 |
| 설계 | ADR-0022, LLD-0026 |
| 현재 상태 | 측정 환경 입력 대기 |

## 확정된 실행 조건

- 워밍업: 60초
- 측정: 회차당 5분
- 반복: 조건별 3회, 중앙값 사용
- 동시 시니어: 1, 10, 25, 50, 100명
- 전송: 단건 1건/초, 배치 15건/15초
- 경로: 정상, 긴급
- 비교: 실제 AI, 결정론적 AI stub, AI 직접 호출

## 실행 전 필요한 외부 입력

- 테스트 전용 시니어 100명의 memberId와 JWT
- 운영 예상 동시 시니어 수와 향후 1년 목표 규모
- 정상 ACK와 위급 알림의 p95·p99 SLO
- 운영과 동등한 API·AI·DB·Redis 사양
- 긴급 테스트용 FCM stub URL과 테스트 Firebase 자격증명

## 결과

아직 전체 매트릭스를 실행하지 않았다. 현재 호스트에서 Docker daemon이 실행 중이지 않고 위 외부 입력이
확정되지 않아 로컬 수치를 운영 병목이나 개선안 선택 근거로 사용하지 않는다.

실행 후 `benchmarks/heart-rate/<timestamp>-<environment>/aggregate.json`의 중앙값을 아래에 옮긴다.

| 환경 | 경로 | 판정 | VUS | ACK p95 | ACK p99 | 오류율 | 처리량 | 포화 여부 |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| - | - | - | - | - | - | - | - | - |

## 병목 판정

측정 전이므로 결정하지 않는다. 다음을 모두 비교한 뒤 판정한다.

- WebSocket p99와 AI 직접 p99 차이
- 실제 AI와 stub의 `heart.ai.detection` p99 차이
- 정상과 긴급 stub의 `heart.emergency.notification`, `fcm.send` p99 차이
- 지연 급증 시 `executor.queued`, `hikaricp.connections.pending`, CPU, 메모리, GC pause의 동시 증가 여부
- 유실, 중복, 사용자별 순서 역전과 timeout 발생 시점

## 개선안 결정

SLO와 전체 측정 결과가 없으므로 동기 유지, bounded queue, 외부 브로커, AI batch 중 하나를 아직
선택하지 않는다. 결과가 채워진 뒤 후속 ADR과 구현 이슈를 생성한다.
