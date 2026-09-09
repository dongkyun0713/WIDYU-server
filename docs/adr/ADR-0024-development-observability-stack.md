# ADR-0024: 개발 서버 오류 관측에 Grafana Loki를 사용한다

| 항목 | 값 |
| --- | --- |
| 상태 | Accepted |
| 날짜 | 2026-09-09 |
| 관련 | LLD-0027, #566 |

## 맥락 (Context)

개발 API 오류를 프론트엔드 개발자가 응답과 서버 로그로 연결해 확인할 방법이 필요하다. 기존 Prometheus, Grafana, Loki, Promtail은 개발 배포에서 시작하지 않는다.

## 결정 (Decision)

Promtail이 개발 컨테이너 로그를 Loki로 수집하고 Grafana에서 조회한다. 요청 traceId를 응답 헤더·오류 응답·로그에 함께 기록하며, Grafana Alerting은 Discord에 오류 요약과 탐색 링크만 보낸다. 관측 도구 포트는 loopback 바인딩을 유지한다.

## 고려한 대안 (Considered Options)

1. **Sentry 신규 도입** — 예외 그룹화가 편리하지만 SDK·비용·계정 관리가 추가된다.
2. **SSH로 Docker 로그 직접 조회** — 간단하지만 프론트 개발자의 확인 경로가 느리다.
3. **기존 Grafana·Loki 재사용** — 이미 있는 인프라를 사용하지만 대시보드와 알림 규칙 설정이 필요하다.

## 결과 (Consequences)

traceId로 프론트 오류 응답과 Loki 로그를 연결한다. Loki 데이터와 모니터링 컨테이너가 Oracle 디스크·메모리를 사용하며 Discord webhook은 서버 환경변수로 관리한다.
