# 심박 STOMP 부하 테스트

## 준비

1. 테스트 전용 DB·Redis·API·AI를 준비한다.
2. `k6/heart-rate-users.example.csv`를 `k6/heart-rate-users.local.csv`로 복사한다.
3. 서로 다른 시니어 100명의 `member_id`, 유효한 access token을 입력한다.
4. 긴급 경로에서는 `FIREBASE_MESSAGING_URL`을 FCM stub으로 고정하고 테스트 Firebase 자격증명만 사용한다.

결정론적 AI/FCM 응답 서버는 다음과 같이 실행한다.

```bash
python3 scripts/k6/heart_rate_stub.py --port 5001 --ai-mode normal
```

API의 `AI_SERVER_URL`은 `http://host.docker.internal:5001`, `FIREBASE_MESSAGING_URL`은
`http://host.docker.internal:5001/v1/projects/widyu-load-test/messages:send`로 지정한다.
긴급 stub 비교는 `--ai-mode emergency`로 재시작한다.

## 전체 실행

기본값은 60초 워밍업, 5분 측정, 1·10·25·50·100 VUS, 조건별 3회다.
Timer percentile과 AI의 사용자별 상태가 회차 사이에 누적되지 않도록 `CASE_RESET_SCRIPT`는 API·AI를
재시작하고 준비가 끝난 뒤 종료해야 한다. 실행기는 이후 `HEALTH_URL`이 응답할 때까지 기다린다.

```bash
USER_DATA=k6/heart-rate-users.local.csv \
RUN_LABEL=stub-normal \
STUB_METRICS_URL=http://localhost:5001/metrics \
API_IMAGE=<digest> \
AI_IMAGE=<digest> \
DB_VERSION=<version> \
REDIS_VERSION=<version> \
RESOURCE_LIMITS='api=2cpu/2GiB,ai=2cpu/2GiB' \
DATA_SCALE='members=100,heart_events=0' \
CASE_RESET_SCRIPT=/absolute/path/to/reset-heart-loadtest.sh \
bash scripts/k6/run_heart_rate_benchmark.sh
```

실제 AI 환경은 `RUN_LABEL=actual-ai`, `AI_URL`과 API의 `AI_SERVER_URL`을 실제 테스트 AI로 바꿔
같은 명령을 실행한다. 실행 중 Actuator와 Docker 자원은 5초마다 수집한다.

## 짧은 검증

전체 측정 전 연결과 결과 형식만 확인할 때 사용한다.

```bash
VUS_LEVELS=1 REPEATS=1 WARMUP_SECONDS=1 MEASURE_SECONDS=5 \
PATHS=single ROUTES=normal TARGETS='websocket ai' \
USER_DATA=k6/heart-rate-users.local.csv \
bash scripts/k6/run_heart_rate_benchmark.sh
```

## 결과

원본은 `benchmarks/heart-rate/<UTC timestamp>-<RUN_LABEL>/`에 저장된다. `aggregate.json`은 조건별
3회 p95·p99 중앙값과 오류·유실·중복·순서 역전, 포화 후보를 담는다. 의사결정에 사용한 원본은
시크릿이 없는지 확인한 뒤 커밋한다.
