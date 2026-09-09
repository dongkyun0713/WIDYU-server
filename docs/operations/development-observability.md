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

Oracle `/home/ubuntu/.env`에는 강한 `GRAFANA_ADMIN_PASSWORD`를 지정한다. Discord 오류 알림을 쓰려면 `DISCORD_WEBHOOK_URL`과 Grafana 접근 주소인 `GRAFANA_EXTERNAL_URL`도 지정한다. webhook URL은 Git과 GitHub Actions Secret에 저장하지 않는다.
