# LLD-0112: DEV 배포 중 앱 중단·재가동 알림

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #906 |
| 관련 ADR | - |
| 작성자 | Codex |
| 작성일 | 2026-10-27 |

## 1. 목적 / 배경

사용자가 원하는 알림은 배포 때문에 앱이 멈추는 시점과 다시 서비스를 시작하는 시점이다. DEV 배포의 실제 컨테이너 중지와 HTTPS 헬스체크 성공을 각각 알린다. 일반 배포 성공 메시지는 중복이라 생략한다.

## 2. 범위

DEV Actions workflow·기존 Discord 배포 스크립트·SSH 출력 중계 스크립트·테스트·문서. 일반 장애 감시, Grafana/relay 변경은 포함하지 않는다. PROD 배포 결과·DEV 배포 실패·인증서 실패는 유지한다. 실제 배포·시험 알림·운영 컨테이너 중지는 실행하지 않는다.

## 3. 인터페이스 / API

SSH stdout 신호는 `WIDYU_DEPLOY_EVENT=app_stopped|app_restarted|app_rollback`다. runner의 forward-deploy-events.sh가 정확한 세 신호만 처리한다. 기존 notify-discord-deploy.sh의 RESULT로 세 값을 추가한다. webhook은 기존 Actions secret이며 서버·SSH 인수에 넘기지 않는다.

| 확인된 상태 | 알림 제목 |
| --- | --- |
| 배포 전 실행 중이던 앱 stop 성공 후 Running=false | 🟠 [DEV] 서버 배포로 인한 앱 중단 |
| 새 앱 기동·Nginx 설정/재로드·HTTPS health 성공 | 🟢 [DEV] 앱 재가동 완료 |
| 새 앱 기동 실패 후 이전 이미지 기동·Nginx 재로드·HTTPS health 성공 | 🟡 [DEV] 이전 버전 앱 재가동 완료 |

처음 배포하거나 앱이 이미 중지 상태면 중단 메시지는 보내지 않는다. 일반 DEV 성공은 NOTIFY_SUCCESS=false로 생략한다. 메시지는 원인·커밋·배포 실행 링크를 담고 멘션은 차단한다.

## 4. 데이터 모델

DB·ERD·애플리케이션 API 변경 없음. 중지 알림을 정확한 시점에 보내기 위해 기존 실행 중 앱만 명시적으로 stop한 뒤 기존 force-recreate를 진행한다. 이미지 pull·의존 서비스 준비는 stop 전에 끝낸다.

## 5. 처리 흐름

1. 이미지·MySQL/Redis/AI 준비 후 기존 이미지와 실행 상태를 읽는다. 실행 중이면 stop하고 중지 상태를 다시 확인한 뒤 app_stopped를 출력한다. 확인 실패 시 배포는 실패하며 중단 성공을 알리지 않는다.
2. 기존 compose 기동·롤백 절차를 진행한다. 새 버전 기동이 실패하면 이전 이미지로 복구를 시도한다. 이전 앱의 HTTPS health까지 성공한 경우에만 app_rollback을 출력하고 배포 자체는 exit1을 유지한다.
3. 새 버전 경로는 Nginx/인증서와 HTTPS health 확인 후 app_restarted를 출력한다. 헬스체크 실패에는 재가동 완료를 출력하지 않는다.
4. runner는 SSH 출력을 즉시 읽어 단계별로 기존 Discord notifier를 호출한다. set -euo pipefail로 SSH 실패를 보존하고 알림 누락/전송 실패는 배포 결과를 바꾸지 않는다. webhook을 원격 호스트에 저장하지 않는다.

## 6. 예외 / 에러 처리

webhook 미설정·HTTP 실패는 경고 로그로 끝내며 재시도/배달 보장은 추가하지 않는다. 배포 취소·SSH 단절 시 단계 알림이 누락될 수 있다. 정상 재가동 알림 이후 앱이 다시 중단되는 일반 장애는 이 기능에서 감지하지 않는다.

## 7. 인수조건

- [x] AC1: 실제 stop 확인 후에만 중단 신호다. 최초/이미중지 배포에는 중단 신호가 없다.
- [x] AC2: 새 앱 HTTPS health 성공 뒤에만 재가동 신호다.
- [x] AC3: 롤백 성공은 별도 메시지이며 배포 exit1, 롤백·health 실패 시 복구 신호가 없다.
- [x] AC4: runner 중계는 정확한 신호만 알림으로 바꾸고 SSH 실패를 보존한다. webhook 실패는 배포 실패를 만들지 않으며 URL을 출력하지 않는다.
- [x] AC5: 메시지 원인·멘션차단, 기존 PROD/실패 알림 유지, DEV 일반 성공 생략.
- [x] AC6: workflow 본문을 대역 명령으로 실행한 테스트·shell 구문·YAML 파싱·하네스·자체 검수를 수행했다. CI Python 17건 통과. backend 변경이 없어 Gradle/npm은 하네스가 생략했다. review APPROVE. 실제 Docker/SSH·Discord·배포는 미실행이다.

## 8. 영향 범위 / 배포

수정된 DEV workflow가 실행되는 배포부터 적용된다. 기존 DISCORD_DEPLOY_WEBHOOK_URL을 재사용한다. Grafana·Prometheus·relay·DB 변경 없음. 실제 Docker/SSH/Discord 동작은 미실행이며 다음 승인된 배포에서 단계·채널 수신 대조가 필요하다.

## 9. 미결정 사항 (Open Questions)

없음. 사용자가 주기적 감시가 아닌 배포에 따른 중단·재가동 알림으로 명확히 정정했다.

## 10. 참고

- [기존 Discord 알림](LLD-0084-discord-alert-ai-analysis.md)
