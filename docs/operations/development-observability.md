# 개발 서버 오류 관측

`docker compose`는 Grafana(3000), Prometheus(9090), Loki(3100), Node Exporter(9100)를 Oracle loopback에만 바인딩한다. 외부에 포트를 열지 않는다.

프론트 개발자는 개인 SSH 계정으로 다음 터널을 열어 Grafana에 접속한다.

```bash
ssh -L 3000:127.0.0.1:3000 <ssh-user>@<oracle-host>
```

브라우저에서 `http://localhost:3000`에 접속한 뒤 **WIDYU Development / WIDYU Development Errors** 대시보드를 연다. Loki Explore에서는 다음 LogQL로 특정 오류를 찾는다.

```logql
{service="widyu-api-dev", environment="dev"} |= "ERROR"
```

응답의 `X-Trace-Id` 또는 JSON `traceId`를 복사해 로그 본문에서 검색한다.

Oracle `/home/ubuntu/.env`에는 다음 값을 모두 지정한다. 값이 없으면 배포가 실패하므로 기본 비밀번호나 localhost 링크로 실행되지 않는다.

```dotenv
GRAFANA_ADMIN_PASSWORD=<strong-password>
DISCORD_WEBHOOK_URL=<discord-webhook-url>
GRAFANA_EXTERNAL_URL=http://localhost:3000
```

Discord 알림 링크는 Grafana SSH 터널을 연 브라우저에서 접근하므로 `GRAFANA_EXTERNAL_URL`에 `http://localhost:3000`을 넣는다. Grafana를 별도 HTTPS 도메인으로 공개한 경우에만 그 도메인을 사용한다. webhook URL은 Git과 GitHub Actions Secret에 저장하지 않는다. Grafana는 개인 계정을 만들어 사용하고 계정을 공유하지 않는다. 팀 이탈 시 즉시 계정을 삭제하며, 관리자 비밀번호와 Discord webhook은 분기마다 또는 유출이 의심되면 즉시 교체한다. Loki 로그 보존 기간은 7일이다.
