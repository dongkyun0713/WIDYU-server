# LLD-0084: 개발 서버 에러·배포 Discord 알림과 AI 분석

> 이 문서는 이슈 #752의 구현과 PR 검수 기준이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Review (2026-10-12, 구현·로컬 E2E 검증 완료; dev 배포 확인 전) |
| Issue | #752 |
| 관련 ADR | - |
| 선행 | [LLD-0027](LLD-0027-development-error-observability.md) 개발 서버 오류 관측, [ADR-0024](../adr/ADR-0024-development-observability-stack.md) |
| 관련 이슈 | #607 (운영 알림 분리, 이 문서 범위 밖) |
| 작성자 | Claude / WIDYU-server-alert-752 |
| 작성일 | 2026-10-12 |

## 1. 목적 / 배경

개발 서버 Discord 알림이 `error: [no value]: [no value]`와 빈 traceId만 보여 줘서, 알림만으로는 무엇이 실패했는지 알 수 없었다. 원인은 세 가지였다.

- **regexp가 줄을 거르지 않음**: LogQL `regexp`는 패턴이 맞지 않는 줄을 버리지 않고 레이블만 비운다. 그래서 `[EXCEPTION]` 형식이 아닌 ERROR 줄도 레이블이 빈 알림이 됐다.
- **`$labels`가 빈 값으로 치환됨**: Grafana provisioning 파일의 `$labels`는 환경변수로 치환돼 빈 값이 된다(`$$` 이스케이프 필요).
- **내용 없는 해소 알림**: 로그 규칙이 1분 만에 해소되면서 내용 없는 해소 알림이 반복됐다.

이번 작업에서 알림을 다음처럼 바꾼다.

- 첫 줄에 기능 이름, 그다음 줄에 엔드포인트, 요청자에서 대상으로, 예외를 보여 준다.
- API 실패, 스케줄러 실패, 푸시 최종 실패, 배포 결과를 구분한다.
- 같은 Discord 메시지에 Claude 분석을 덧붙인다.

## 2. 범위

### In scope
- **변경 모듈**: widyu-api, monitoring(Grafana provisioning, 새 alert-relay), GitHub Actions
- **`[EXCEPTION]` 로그 확장**: 기능 이름(Swagger summary), HTTP 메서드, 매핑 경로, 요청자(`역할#회원ID`), ID 경로 변수를 추가하고, 스택을 원인(Caused by)까지 한 줄로 남긴다.
- **스케줄러 실패 로그**: 처리되지 않은 예외를 `[SCHEDULER] Task= ExceptionType= Message= StackTrace=` 한 줄로 남긴다.
- **전용 스케줄러**: `@Scheduled`를 `SchedulingConfigurer`로 지정한 전용 스케줄러(`scheduling-N`, CPU 수만큼 병렬)에서 돌린다. 지정하지 않으면 `@EnableWebSocketMessageBroker`의 `messageBrokerTaskScheduler`(`MessageBroker-N`)가 쓰여 에러 핸들러가 붙지 않는다.
- **FCM 최종 실패 로그**: 최종 실패(EXHAUSTED/EXPIRED)로 바뀌는 순간을 `[FCM_FAILED]` 한 줄로 남긴다. 대상 경로는 전송 실패(finish), 선점 시 만료·재시도 소진(claim), 전송 직전 만료(preflight), 저장 데이터 손상이다.
- **배포 커밋 로그**: 실행 중인 커밋을 `[BUILD] Version=<sha>`로 시작할 때와 매일 04:00에 남긴다.
- **WebSocket 실패 로그**: `@MessageMapping` 처리 중 난 서버 오류를 `[WS_EXCEPTION] Api= Destination= Actor= ExceptionType= Message= StackTrace=` 한 줄로 남긴다. 업무 규칙 4xx·입력 검증·형식 오류는 WARN으로 남긴다. 서버 오류의 내부 메시지는 클라이언트에 돌려주지 않는다(REST 500과 같은 기준).
- **4xx 잡음 제거**: 입력 검증 실패 로그를 ERROR에서 WARN으로 내린다.
- **5xx 업무 예외**: 응답이 5xx인 `BusinessException`(결제 실패, 파일 업로드 실패, 외부 OAuth·SMS 통신 실패 등)은 WARN 업무 로그 대신 `[EXCEPTION]` ERROR로 남긴다. "5xx 응답 = API 실패 알림"이 예외 종류와 상관없이 성립하게 하기 위해서다.
- **Grafana 규칙**: 규칙 5개(API·WebSocket·스케줄러·푸시·기타 ERROR)로 나누고 식별 레이블만 남긴다. 해소 알림을 끄고 webhook으로 relay에 보낸다.
- **alert-relay**: Python 컨테이너로, SQLite 작업 큐, 대표 로그 재조회, 마스킹, Discord 전송·수정, 배포 커밋 소스 기반 Claude 분석, 분석 이력과 재사용, 하루 호출 상한, 무효화·백업 명령을 맡는다.
- **배포 알림**: dev·prod 배포 결과와 dev 인증서 갱신 실패를 Discord로 알린다.

### Out of scope
- 운영(prod) 에러 알림, 5xx 비율·SLO burn-rate·서버 다운 알림 (#607)
- 4xx 비즈니스 예외 알림
- relay 다중 인스턴스·고가용성 (dev 서버 단일 컨테이너)

## 3. 인터페이스 / API

외부 HTTP API 변경은 없다. 내부 인터페이스는 다음과 같다.

```http
POST http://alert-relay:8080/grafana   # Grafana webhook (Docker 내부망만, 포트 비공개)
GET  http://alert-relay:8080/health
```

로그 형식(알림 규칙과 relay가 읽는 계약):

```text
[EXCEPTION] Time=.. Api=<summary|Controller.method|알 수 없는 API> Method=POST Path=/api/v1/albums/{albumId}|- Actor=USER#12|anonymous Target=albumId=7|- ExceptionType=<FQCN> Message=.. RequestURI=.. StackTrace=<\n으로 이은 프레임, Caused by 포함>
[SCHEDULER] Task=PaymentRecoveryScheduler.recover ExceptionType=<simple> Message=.. StackTrace=..
[WS_EXCEPTION] Api=<Controller.method> Destination=/app/heart-rate/send-single Actor=USER#34 ExceptionType=<FQCN> Message=.. StackTrace=..
[FCM_FAILED] Type=<NotificationType|dataType|FcmCategory> Emergency=true Related=<id|null> Recipient=<id> Attempts=n Reason=INVALID_TOKEN|REJECTED|RETRY_EXHAUSTED|EXPIRED|INVALID_PAYLOAD
[BUILD] Version=<git sha>
```

## 4. 데이터 모델

DB·엔티티 변경 없음. relay의 SQLite(`/data/relay.sqlite3`, 볼륨 `alert_relay_data`)는 다음 테이블을 둔다.

| 테이블 | 용도 |
| --- | --- |
| `jobs` | 알림 작업. `pending → sent(AI 대기) → done`, 실패 시 `attempts`·`next_at` 백오프, 6회 실패면 `failed`. `dedupe_key` UNIQUE |
| `ai_analyses` | 분석 이력. 지우지 않는다. 키 = 환경·kind·기능·예외 종류·메시지 형태·가장 안쪽 앱 프레임·프롬프트 버전. 분석 당시 배포 커밋·모델·프롬프트 버전·무효화 시각을 함께 저장 |
| `ai_quota` | 날짜별 AI 호출 수. 상한 조건을 UPDATE 안에 둬 재시작해도 넘지 않음 |

## 5. 처리 흐름

1. **로그 수집**: 앱이 로그를 남기면 promtail이 Loki로 보낸다.
2. **규칙 평가**: Grafana가 1분마다 규칙을 평가한다. 알림 레이블은 다음 식별 정보만 둔다.
   - API: `api`, `method`, `path`, `exceptionType`
   - WebSocket: `api`, `destination`, `exceptionType`
   - 스케줄러: `task`, `logger`, `exceptionType`
   - 푸시: `type`, `emergency`, `reason`
   - 기타 ERROR: `logger`

   traceId·회원 ID·메시지는 레이블로 두지 않는다. 같은 오류 100건도 알림 인스턴스 하나다. 정책은 `group_by: ['...']`(레이블 조합 하나 = 메시지 하나), `group_wait` 30s, `group_interval` 5m, `repeat_interval` 4h다.
3. **작업 저장**: relay가 webhook의 firing 알림을 `fingerprint + startsAt + 15분 구간` 키로 SQLite에 저장하고 200을 응답한다. 저장에 실패하면 500을 응답해 Grafana가 재시도한다. 해소 알림은 무시한다.
4. **기본 알림 전송**: 워커 1개가 작업을 처리한다.
   1. 기능·시간 범위(알림을 받은 시각 기준 15분)로 Loki에서 대표 로그를 최대 200건 조회하고, 앞 3건을 파싱한다.
   2. 메시지와 스택을 마스킹한다.
   3. Discord에 `?wait=true`로 보내고 message id를 저장한다.
5. **AI 분석**: AI 대상(api·scheduler·error)이고 `ANTHROPIC_API_KEY`가 있으면 진행한다.
   1. 오류 직전 `[BUILD] Version=`(최대 7일 전)으로 커밋을 찾는다.
   2. 그 커밋의 소스에서 스택의 앱 프레임 ±20줄을 GitHub raw로 가져온다.
   3. 저장 분석 재사용 여부를 판단한다. 아래 조건을 **모두** 만족하면 Claude를 부르지 않고 "YYYY-MM-DD 분석 재사용(커밋 …)"을 붙인다.
      - 같은 키다.
      - 무효화되지 않았다.
      - 재사용 기간(`ALERT_AI_REUSE_DAYS`, 기본 7일) 안이다.
      - 관련 파일이 바뀌지 않았다. 저장 당시 커밋과 현재 커밋이 같거나, GitHub compare 결과 다음 파일이 하나도 바뀌지 않았어야 한다.
        - 스택에 나온 모든 앱 클래스 파일
        - `src/main/resources/` 아래 파일
        - `build.gradle` 계열 파일

      커밋을 모르거나, compare가 실패하거나, 파일 목록이 300개에서 잘리면 재사용하지 않는다.
   4. 재사용하지 않으면 하루 상한 안에서 Claude를 1회 호출한다(effort medium, 300초 제한). 응답은 structured outputs(`output_config.format`의 JSON 스키마)로 강제한다.
      - 필드: `cause`, `confidence`(high/medium/low), `evidence`[{`location`, `description`}], `checks`[], `fix`
      - relay가 스키마를 한 번 더 확인한 뒤 Discord 형식으로 그린다: 원인 추정·신뢰도 / 근거 / 확인할 것 / 수정 방향, 목록은 최대 3개, 값마다 길이를 자른다.
      - 거절·`max_tokens` 잘림·스키마 위반은 분석 실패로 처리하고 이력에 저장하지 않는다.
   4. 결과를 같은 메시지에 PATCH로 덧붙인다.
6. **배포 알림**: GitHub Actions가 배포 결과를 `scripts/ci/notify-discord-deploy.sh`로 보낸다. 새 push로 취소된 배포는 보내지 않는다.

## 6. 예외 / 에러 처리

| 상황 | 동작 |
| --- | --- |
| Discord 전송 실패 | 작업을 `30s·2^n`(최대 30분) 백오프로 재시도하고, 6회 실패하면 `failed`로 둔다 |
| relay 재시작 | SQLite에 남은 `pending`·`sent` 작업을 이어서 처리한다 |
| Discord가 받았지만 응답이 시간 초과 | 재시도해서 같은 메시지가 두 번 갈 수 있다. 최소 한 번 전달 설계상 받아들인다 |
| 같은 webhook 재전송 | `dedupe_key`가 같으면 무시한다 |
| Loki 조회 실패 | 작업 재시도 |
| 대표 로그 없음 | "상세 로그를 찾지 못했습니다" + 대시보드 링크 |
| 배포 커밋을 찾지 못함 / 파일 404 | develop으로 대신하지 않는다. "⚠️ 소스 조회 실패(사유) — 스택 위치만으로 추정"으로 표시한다 |
| Claude 오류·한도·거절·빈 답변 | 기본 알림은 유지하고 "분석 실패: 사유"를 덧붙인다. 작업 재시도로 넘기지 않아 하루 호출 수를 다시 쓰지 않고, 실패 문구는 이력에 저장하지 않는다 |
| 분석 이력 조회·저장 실패(SQLite) | 재사용만 포기하고 분석·알림은 계속한다. 기본 알림 단계는 이력을 쓰지 않는다 |
| 잘못된 저장 분석 | `python relay.py --invalidate <키 일부>`로 무효화하면 다음 발생 때 다시 분석한다. 이전 분석은 이력으로 남는다 |
| 볼륨·호스트 디스크 손실 | 볼륨은 컨테이너 교체에는 남지만 디스크 손실은 막지 못한다. `--backup`으로 만든 파일을 호스트 밖에 보관한다(운영 문서) |
| `ANTHROPIC_API_KEY` 없음 | 기본 알림만 보낸다 (relay는 정상 기동) |
| 배포 알림 전송 실패·secret 없음 | 배포 결과에 영향 없음 (`continue-on-error`, 스크립트는 항상 0으로 끝남) |

**개인정보**
- **Discord**: 회원 ID와 역할, ID 경로 변수만 보낸다. 결제 키처럼 ID가 아닌 경로 값은 로그 단계에서 빼고, 토큰·푸시 본문은 로그에 넣지 않는다.
- **Discord·AI 공통 마스킹**: 개인정보와 요청마다 달라지는 값만 가린다. 대상은 입력값인 따옴표 값, 이메일, UUID, 7자리 이상 숫자, `*id=숫자`, `phone`/`token`/`password`/`name`/`session` 값이다. HTTP 상태(401·503), SQL 오류 코드(1062)·SQLState(23000), 제약조건·테이블·컬럼·인덱스 이름은 원인을 가르는 정보라 남긴다.
- **AI 추가 제외**: 요청자, 대상, traceId, 실제 URI도 뺀다.
- **멘션 방지**: Discord 메시지는 `allowed_mentions.parse=[]`로 보낸다.

## 7. 인수조건 (Acceptance Criteria)

- [x] 핸들러가 정해진 5xx는 `[EXCEPTION]`에 Swagger summary, 메서드, 매핑 경로, 요청자, ID 경로 변수를 남긴다. summary가 없으면 `Controller.method`, 핸들러가 없으면 `알 수 없는 API`와 경로 `-`를 남긴다(실제 URI는 남기지 않음). (`ApiRequestContextTest`, `GlobalExceptionHandlerTest`)
- [x] 스택은 원인까지 한 줄로 남고, 원인이 순환해도 멈춘다. (`StackTraceLogTest`)
- [x] 스케줄러 미처리 예외는 `[SCHEDULER]` 한 줄로 남고 다시 던지지 않는다. WebSocket 메시지 브로커가 있어도 `@Scheduled`는 이 핸들러가 붙은 `scheduling-` 스레드에서 돌고, 예외 뒤에도 다음 실행이 이어진다. (`SchedulerErrorHandlerTest`)
- [x] FCM 최종 실패(전송 실패·선점 시 만료)만 `[FCM_FAILED]`를 남기고, 재시도 예정 실패는 남기지 않는다. 토큰·본문은 없다. (`FcmOutboxTransactionsTest`)
- [x] `[BUILD] Version=`을 남긴다. (`BuildVersionLogTest`)
- [x] 입력 검증 실패는 WARN이다.
- [x] 5xx 업무 예외는 `[EXCEPTION]` ERROR로, 4xx 업무 예외는 WARN 업무 로그로 남는다. (`GlobalExceptionHandlerTest`)
- [x] 요청 안의 ERROR 가운데 그 요청이 API 실패 알림으로 이미 간 줄(같은 traceId의 `[EXCEPTION]`)은 기타 ERROR에서 빠지고, 남는 줄이 없으면 기타 알림을 보내지 않는다. 요청 밖(traceId=none)의 ERROR는 그대로 알린다. (`test_relay.py`)
- [x] WebSocket 메시지 처리 서버 오류는 기능·목적지·요청자를 담은 `[WS_EXCEPTION]` 한 줄 ERROR로 남고, 내부 메시지는 응답에 담지 않는다. 업무 규칙 4xx는 WARN이고, 5xx 업무 예외는 ERROR다. 실제 STOMP 처리 경로에서도 Spring이 핸들러와 헤더를 넘겨 같은 로그가 남는다. (`WebSocketExceptionHandlerTest`)
- [x] 같은 API 오류 100건이 알림 인스턴스 하나·Discord 메시지 하나가 되고, "최근 15분 100건 · 앞 3건 표시"를 보여 준다. (로컬 E2E)
- [x] 예전 형식 `[EXCEPTION]` 줄과 WARN은 알림이 되지 않는다(레이블이 빈 알림 없음). (로컬 E2E)
- [x] 중복 webhook은 한 번만 처리하고, Discord 장애 중 relay가 재시작돼도 복구 후 한 번 전송한다. (로컬 E2E)
- [x] 배포 커밋의 소스 줄을 AI 입력에 넣고, 커밋을 찾지 못하거나 파일이 없으면 "소스 조회 실패"로 표시한다. (로컬 E2E, Claude 호출은 대역)
- [x] 분석 재사용 동작 (`test_relay.py`)
  - 같은 원인은 재사용하고 재시작 뒤에도 유지된다.
  - 관련 없는 배포는 재사용한다. 스택 파일·설정·빌드 파일이 바뀌었거나, 확인할 수 없거나, 재사용 기간이 지났거나, 수동으로 무효화하면 재분석한다.
  - 메시지 형태가 다르면(예: 503과 401) 따로 분석하고, 긴 ID만 다르면 재사용한다.
  - 연속으로 들어온 같은 오류는 AI를 1회만 호출한다.
  - 실패·빈 답변은 저장하지 않는다.
  - 이력 저장소가 고장 나도 기본 알림은 간다.
  - 백업 파일에 이력이 들어 있다.
- [x] AI 답변 형식은 JSON 스키마로 강제되고, 거절·잘림·스키마 위반 답변은 분석 실패로 처리돼 저장되지 않는다. 그려진 분석은 네 구획과 목록 3개 제한을 지킨다. (`test_relay.py`)
- [x] AI 입력에 회원 ID·traceId·전화번호·이메일·실제 URI가 없고, 마스킹 뒤에도 HTTP 상태·SQL 코드·제약조건 이름은 남는다. (`test_relay.py`)
- [x] 배포 알림은 성공·실패(실패 단계)를 보내고, 취소된 배포는 건너뛰며, webhook이 없어도 실패하지 않는다. (`scripts/ci/test_notify_discord_deploy.py`)
- [ ] dev 배포 후 실제 Discord 채널에서 기본 알림과 AI 분석이 같은 메시지로 보인다. (배포 후 확인)
- [x] `./gradlew :backend:widyu-api:test` 대상 테스트가 통과한다.

## 8. 영향 범위 / 마이그레이션

- **로그 형식**: `[EXCEPTION]` 형식이 바뀐다. 배포 전 형식의 줄은 새 규칙에서 무시된다(레이블 필터).
- **스케줄러 실행 스레드**: `@Scheduled` 작업이 `MessageBroker-N`에서 전용 `scheduling-N` 스레드로 옮겨진다. 병렬도는 CPU 수로 같고, WebSocket 브로커 하트비트 스케줄러와 더는 스레드를 공유하지 않는다.
- **dev 서버 설정**
  - `.env`에 `ANTHROPIC_API_KEY`를 추가해야 AI 분석이 켜진다(선택).
  - `DISCORD_WEBHOOK_URL`은 Grafana 대신 relay가 쓴다.
  - `docker-compose.dev-server.yml`이 widyu-api에 `APP_COMMIT_SHA=${IMAGE_TAG}`를 넘긴다.
- **GitHub secret**: `DISCORD_DEPLOY_WEBHOOK_URL`은 에러 알림과 **다른** webhook이다. 에러 webhook을 GitHub에 두지 않는 기존 원칙(운영 문서)을 지키고, 배포 webhook만 따로 폐기할 수 있게 한다. 없으면 배포 알림만 건너뛴다.
- **운영 문서**: `docs/operations/development-observability.md`에 알림 종류·AI 분석·secret 원칙을 반영했다.
- **배포 순서**: `deploy-dev.yml`이 모니터링 스택 재기동 뒤 `alert-relay`를 `--build`로 다시 띄운다.

## 9. 미결정 사항 (Open Questions)

- 없음. AI 모델 기본값은 `claude-opus-5`, 하루 상한은 50회, 재사용 기간은 7일이고, 환경변수로 바꿀 수 있다.
- 알려진 한계
  - 실행 중에 `.env`로 주입되는 설정은 저장소에 없어 변경을 감지하지 못한다. 필요하면 수동 무효화한다.
  - GitHub 비인증 API 한도(시간당 60회)를 넘으면 compare가 실패하고, 이때는 재사용하지 않고 재분석한다.

## 10. 참고

- [Loki log queries](https://grafana.com/docs/loki/latest/query/log_queries/): regexp 파서는 줄을 거르지 않는다.
- [Grafana 파일 provisioning](https://grafana.com/docs/grafana/latest/alerting/set-up/provision-alerting-resources/file-provisioning/): `disableResolveMessage`, `$$` 이스케이프
- [Grafana 알림 그룹](https://grafana.com/docs/grafana/latest/alerting/fundamentals/notifications/group-alert-notifications/): `group_by`, `repeat_interval`
- [Google SRE: Monitoring Distributed Systems](https://sre.google/sre-book/monitoring-distributed-systems/): 알림은 사람이 바로 행동할 수 있어야 한다.
