# 개발 서버 오류 관측

`docker compose`는 Grafana(3000), Prometheus(9090), Loki(3100), Node Exporter(9100)를 Oracle loopback에만 바인딩한다. Grafana는 Nginx가 HTTPS 경로로 프록시하며, 나머지 도구 포트는 외부에 열지 않는다.

프론트 개발자는 `https://dev.widyu.store/grafana/`에서 Grafana에 접속한다. Grafana 계정을 사용하며 익명 접근과 공개 회원가입은 비활성화한다.

로컬 `scripts/docker/dev-up.sh`는 Grafana 대시보드와 데이터소스만 시작한다. alert rule과 알림 중계 서버(`alert-relay`)는 `docker-compose.dev-server.yml`에서 Oracle 개발 서버에만 띄우므로, 로컬 API ERROR 로그는 Discord로 전송되지 않는다.

## Discord 알림 (LLD-0084)

Grafana 규칙은 아래 네 가지로 나뉘고, `alert-relay`가 Discord 메시지를 만든다. 같은 기능의 오류는 몇 건이든 메시지 하나로 묶이고, 메시지에 최근 15분 건수와 앞 3건의 요청자·대상·예외·traceId가 붙는다.

| 알림 | 조건 |
| --- | --- |
| 🚨 API 실패 | 처리하지 못한 예외로 5xx가 난 요청. 첫 줄은 Swagger summary(기능 이름) |
| ⏰ 스케줄러 실패 | `scheduling-N` 스레드의 ERROR |
| 📵 푸시 알림 발송 실패 | FCM 재시도를 다 쓴 최종 실패 |
| ⚠️ 기타 ERROR | 위에 속하지 않는 ERROR |

4xx 비즈니스 예외·입력 검증 실패와 해소 알림은 보내지 않는다. `ANTHROPIC_API_KEY`가 있으면 relay가 오류 시점의 배포 커밋 소스와 스택으로 Claude 분석을 같은 메시지에 덧붙인다. AI에는 회원 ID·traceId·실제 URI를 보내지 않고, 예외 메시지의 입력값도 가린다. 하루 분석 수는 `ALERT_AI_DAILY_LIMIT`(기본 50)로 제한한다. 분석은 이력으로 계속 보관하고, 같은 원인이면 `ALERT_AI_REUSE_DAYS`(기본 7일) 안에서 재사용한다. 재사용 조건은 그 사이 스택에 나온 코드, `resources/` 설정, `build.gradle`이 바뀌지 않았다는 것이다.

잘못된 분석을 다시 받으려면 키 일부(예: 기능 이름)로 무효화한다. 다음 발생 때 새로 분석하고, 이전 분석은 이력으로 남는다.

```bash
docker exec widyu-alert-relay python relay.py --invalidate "앨범 업로드 완료"
```

분석 이력과 작업 큐는 Docker 볼륨(`alert_relay_data`)의 SQLite에 있다. 볼륨은 컨테이너를 바꿔도 남지만 호스트 디스크 손실은 막지 못하므로, 필요하면 백업 파일을 만들어 서버 밖에 보관한다.

```bash
docker exec widyu-alert-relay python relay.py --backup /data/backup.sqlite3
docker cp widyu-alert-relay:/data/backup.sqlite3 ./relay-backup-$(date +%F).sqlite3
# 복구: 컨테이너를 멈추고 백업 파일을 볼륨의 relay.sqlite3로 되돌린 뒤 다시 띄운다
docker compose -f docker-compose.yml -f docker-compose.dev.yml -f docker-compose.dev-server.yml stop alert-relay
docker run --rm -v widyu_alert_relay_data:/data -v "$PWD":/backup alpine \
  sh -c 'rm -f /data/relay.sqlite3-wal /data/relay.sqlite3-shm && cp /backup/relay-backup-YYYY-MM-DD.sqlite3 /data/relay.sqlite3 && chown 65534 /data/relay.sqlite3'
docker compose -f docker-compose.yml -f docker-compose.dev.yml -f docker-compose.dev-server.yml up -d alert-relay
```

볼륨 이름은 compose 프로젝트 이름(배포 폴더 이름)에 따라 달라지므로 `docker volume ls | grep alert_relay_data`로 확인한다.

배포 결과(dev·prod)와 dev 인증서 갱신 실패는 GitHub Actions가 Discord로 보낸다.

로그인 뒤 **WIDYU Development / WIDYU Development Errors** 대시보드를 연다. Loki Explore에서는 다음 LogQL로 특정 오류를 찾는다.

```logql
{service="widyu-api-dev", environment="dev"} |= "ERROR"
```

응답의 `X-Trace-Id` 또는 JSON `traceId`를 복사해 로그 본문에서 검색한다.

개발 서버의 5xx 응답에는 프론트 진단용 `debug.exceptionType`, `debug.requestUri`가 포함된다. 예외 메시지와 스택 트레이스는 응답에 노출하지 않으므로 `traceId`로 Grafana 로그를 연다.

Oracle `/home/ubuntu/.env`에는 다음 값을 모두 지정한다. 값이 없으면 배포가 실패하므로 기본 비밀번호나 localhost 링크로 실행되지 않는다.

```dotenv
GRAFANA_ADMIN_PASSWORD=<strong-password>
DISCORD_WEBHOOK_URL=<discord-webhook-url>
GRAFANA_EXTERNAL_URL=https://dev.widyu.store/grafana
# 선택: 없으면 AI 분석 없이 알림만 보낸다
ANTHROPIC_API_KEY=<anthropic-api-key>
```

`GRAFANA_EXTERNAL_URL`은 끝 슬래시 없이 Grafana 공개 주소를 지정한다. Discord 알림 링크는 이 주소와 traceId를 사용해 해당 로그가 필터링된 대시보드를 연다. 에러 알림 webhook URL은 Git과 GitHub Actions Secret에 저장하지 않는다. 배포 알림은 GitHub Actions가 보내야 하므로 **에러 알림과 다른 별도 webhook**을 만들어 GitHub secret `DISCORD_DEPLOY_WEBHOOK_URL`에 둔다(같은 채널에 webhook을 하나 더 만들면 된다). 이 secret이 없으면 배포 알림만 건너뛴다. Grafana는 개인 계정을 만들어 사용하고 계정을 공유하지 않는다. 팀 이탈 시 즉시 계정을 삭제하며, 관리자 비밀번호, Discord webhook, Anthropic API 키는 분기마다 또는 유출이 의심되면 즉시 교체한다. Loki 로그 보존 기간은 7일이다.
