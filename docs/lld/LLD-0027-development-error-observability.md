# LLD-0027: 개발 서버 오류 관측

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #566 |
| 관련 ADR | ADR-0024 |
| 작성일 | 2026-09-09 |

## 1. 목적 / 배경

개발 API 오류를 프론트 응답, Loki 로그, Discord 알림에서 같은 traceId로 연결한다.

## 2. 범위

### In scope
- widyu-api 요청 traceId와 오류 응답·로그 기록
- Promtail Docker 로그 수집, Grafana Loki 대시보드와 Discord 오류 알림
- 개발 배포의 모니터링 컨테이너 기동

### Out of scope
- Sentry 도입과 운영 서버 알림

## 3. 인터페이스 / API

모든 HTTP 응답은 `X-Trace-Id` 헤더를 포함한다. 오류 응답에는 기존 필드를 유지하고 `traceId`를 추가한다.

## 4. 데이터 모델

DB 변경 없음.

## 5. 처리 흐름

1. 필터가 요청 header 또는 UUID를 MDC에 설정한다.
2. 예외 처리기가 같은 traceId를 오류 응답과 ERROR 로그에 기록한다.
3. Promtail이 Docker 로그를 Loki에 전송한다.
4. Grafana가 ERROR 로그를 대시보드와 Discord 알림 규칙으로 조회한다.

## 6. 예외 / 에러 처리

Discord webhook이 없으면 알림 규칙을 만들지 않으며 API 요청에는 영향을 주지 않는다.

## 7. 인수조건 (Acceptance Criteria)

- [ ] 5xx 응답과 ERROR 로그가 같은 traceId를 가진다.
- [ ] Grafana에서 개발 API ERROR 로그를 조회할 수 있다.
- [ ] Discord 오류 알림은 traceId와 Grafana 링크만 전달한다.
- [ ] 관측 도구 포트는 외부 인터페이스에 게시되지 않는다.
- [ ] `./gradlew :backend:widyu-api:test`가 통과한다.

## 8. 영향 범위 / 마이그레이션

기존 API 응답에 선택적 `traceId` 필드이 추가된다. DB 변경 없음.

## 9. 미결정 사항 (Open Questions)

없음.
