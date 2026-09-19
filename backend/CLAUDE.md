# backend/CLAUDE.md

백엔드 도메인 지도·불변식·주의사항입니다. 공통 규칙·아키텍처 패턴은 루트 [CLAUDE.md](../CLAUDE.md)를 참조하세요.
상세 설계·의사결정은 [docs/lld](../docs/lld)·[docs/adr](../docs/adr)에 있으니 해당 도메인 작업 전 먼저 읽으세요.

## Core Domain Modules

`com.widyu.global`은 공통 인프라 (`config`, `security`, `websocket`, `aspect`, `error`, `filter`, `util`, `properties`, `infrastructure`). 도메인 패키지는 아래:

### `auth` — 인증
- Multi-provider OAuth (Apple/Naver/Kakao) + 로컬 SMS 인증
- 토큰 3종: Access(단기, `Bearer`) / Refresh(Redis TTL) / Temporary(회원가입 1회용, 30분)
- SMS 플로우: 발송 → 코드 검증 → Temporary Token → 회원가입 → JWT. Apple은 전화번호 별도 수집. → ADR-0002, LLD-0004

### `member` — 회원 (가족 도메인)
- `Member` + `MemberType`(SENIOR/GUARDIAN), `MemberRole`(ADMIN/USER/TEMPORARY)
- **가족 모델 (다중 시니어)**:
  - `Family`: 가족 그룹, `familyCode`(6자). 시니어 등록 시 자동 생성
  - `FamilyMembership`: 보호자-가족 연결. `isLeader`(방장)·`isRepresentative`(대표 비상연락처)·`nickname`
  - `SeniorProfile`: 시니어 프로필, `Family` FK, `inviteCode`(7자)
  - **핵심 불변식**: 회원 1명 = 가족 1개 (시니어 `SeniorProfile.family` FK / 보호자 `FamilyMembership` unique). 가족 1개에 시니어·보호자 다수 가능
- `PointHistory`: 적립(EARN)/사용(USE) 내역. → ADR-0003

### `album` — 앨범
- 사진/영상 CRUD + S3. `AlbumLike`·`AlbumComment`(댓글+답글 2단계)·`AlbumView`·`AlbumUnlock`
- **불변식**: Feed 쿼리는 `status=ACTIVE`만 (PROCESSING 자동 제외). Status enum: ACTIVE/INACTIVE/DELETED/PROCESSING
- 비동기 업로드: 사진 즉시(sync) → 영상 `@Async`(FFmpeg 썸네일→S3→ACTIVE, 실패 시 DELETED). → LLD-0006·0012·0013, ADR-0004·0010·0011
- 잠금해제: 50포인트, `AlbumUnlock`, 이후 영구 접근

### `fcm` — 푸시 알림
- `@EventListener` 이벤트 아키텍처. `MemberNotificationSetting`(회원×카테고리 unique)
- `FcmCategory`: ALL, ALBUM, TARGET, HEALTH_SCHEDULE, WALK, MEDICINE_SCHEDULE, HEART_MESSAGE, SAFE_ZONE
- 비활성 유저 스케줄 알림(3/5/7일). → LLD-0002·0014

### `pay` — 결제·포인트
- **불변식**: 결제는 포인트를 충전·환수하지 않는다(ADR-0029). 시니어 가입 시 100포인트 지급, 앨범 잠금해제 50포인트 차감. TossPayments 연동
- 결제 트랜잭션 경계·멱등성은 → LLD-0003·0015·0016·0017, ADR-0012

### `goal` — 건강 목표
- `medicine`(복약 스케줄·알람 인증), `walk`(`SeniorProfile.defaultWalkGoal`), `healthschedule`, `addressbookmark`, `home`(가족 멤버 조회)
- **복약 불변식**: 알람 ±30분 이내에만 인증 제출, 같은 날 동일 스케줄 중복 불가, 월별 준수율 통계. → LLD-0007·0008·0009

### `heart` — 심박수 (독립 도메인)
- 심박 측정값 1건을 1초마다 수신 → AI(`POST /api/hr`) 1회 호출 → 즉시 판정·저장
- `alert=true`인 EMERGENCY 시 `HeartRateEmergency` 기록 + 보호자 FCM
- **AI 응답 중 `alert`·`level`만 사용**. `reason`(서맥·빈맥 등 사유)·`layer`(L0 고정임계값/L1 개인기준선)·`baseline_source`는 폐기 — 개인화는 `context=REST`에서만 동작
- REST(`HeartRateController`) + WebSocket(`HeartRateWebSocketController`) 이중 지원
  - 시니어 전송: `/app/heart-rate/send-single`(1건)
  - 보호자 구독 `/topic/heart-rate/{memberId}` / ACK `/user/queue/heart-rate/result`
  - 단건 경로만 지원하며 결과 ACK는 발신 세션에만 전달한다 → ADR-0017, LLD-0023
- **위급 사이클**: 위험 감지 후 5분 유지, 그 안에 재감지되면 마지막 감지 기준 연장. "마지막 감지 + 5분" == "최근 5분 내 감지 존재"이므로 상태를 저장하지 않고 조회 윈도우로만 판정. **그래프 조회 범위 산정에만 사용**하며, `/emergency/recent`는 사이클과 무관하게 최근 15분 내 기록 유무만 반환
- **조회 범위 규칙**: 그래프는 응급 화면이므로 events·max·min·firstEmergency가 **진행 중인 사이클 시작 5분 전 ~ 현재**. 사이클 없으면 빈 배열. `emergencyHistory`의 count·events는 전체 기간이지만 `totalDuration`은 현재 사이클 지속 시간(첫 감지~마지막 감지, 분). 갱신(`/graph/refresh`)은 `since` 지정 시 그 이후 신규 이벤트만, 미지정 시 최근 5개
- 수신한 단건 심박은 AI 판정 후 즉시 최신값에 반영한다.
- AI: Docker `ryuchanghoon/widyu-ai-ver7:latest` port 5000, multi-arch. → LLD-0010·0019·0020, ADR-0008·0013·0014

### `sensor` — 원시 IMU 배치 (연구 수집, v2 형식)
- 워치·폰의 가속도·자이로 배치를 **재표본화 없이** 저장. 배치 1건 = S3 객체 1개(`sensor/{memberId}/{deviceId}/{stream}/{batch_id}.json`) + `sensor_batch` 인덱스 행 1개 → ADR-0030 v2, LLD-0041 v2
- **원문 바이트 보존이 최우선**: 컨트롤러가 `@RequestBody byte[]`로 받아 S3에 그대로 올린다. 재직렬화·정렬·압축 금지. `payload_sha256`·`byte_size`도 원문 기준. 꺼냈을 때 앱이 보낸 본문과 바이트 단위로 같아야 한다(지시서 B2)
- **멱등 키는 앱이 붙인 불변 `batch_id`(ULID)**. UK도 S3 키도 이것. 같은 `batch_id`면 PUT 없이 `DUPLICATE`. 내용이 달라지는 재전송은 새 `batch_id` + `resend` 계보 6필드
- **불변식**: 나노초 시각(`anchor_elapsed_ns`·`t0_elapsed_ns`)은 **JSON 문자열**로 받는다(number면 `SENSOR_PAYLOAD_INVALID`). `dt_ns`는 길이 n−1·`0 < dt ≤ 4294967295`, 값 배열은 n행 3열. 시계 다섯 값은 원본 그대로 두고 `measured_at_start/end_ms`만 서버가 환산
- 가속도·자이로는 **한 배치에 오되 시간축이 독립**. `gyro: null`은 null로 저장(0 배열 금지 — AI가 정지로 읽는다). 생략인지 결측인지는 `collection_mode`로 가린다
- 시각을 단계별로 남긴다: `server_received` → `accepted` → `persisted` = `model_available_at_server`. 하나로 합치지 않고 소급하지 않는다(정책 1.1.5)
- S3 PUT은 수신 스레드에서 동기(`apiCallTimeout` 5s), 인덱스 INSERT는 그 뒤. 서비스에 `@Transactional` 없음. 무결성 예외는 `existsByBatchId` 재조회로 확인될 때만 `DUPLICATE`
- **원시 센서값을 어떤 로그 레벨에도 남기지 않는다** (정책 1.6.7). memberId·stream·seq·accN·gyroN·result만
- 시계 매핑은 `clock_mapping` 테이블(기기 단위 UK `clock_mapping_id`). 같은 id에 다른 다섯 값·다른 기기면 409(`SENSOR_4090`). 등록은 S3 PUT 앞, 자기 트랜잭션 → LLD-0044
- 본문 상한은 `sensor.max-payload-bytes`(기본 32768, `application-sensor.yml`)
- REST `POST /api/v1/sensor/batches`, WebSocket `/app/sensor/batches/send` → ACK `/user/queue/sensor/result`

### `run` — 측정회차·기기 배정·마커 (연구 운영)
- 실증은 기기를 여러 참가자가 돌려 쓴다. 회차가 「누가·어떤 기기를·어디에 차고·언제부터 언제까지」를 묶어 자료 귀속의 다리를 놓는다 → LLD-0045, 지시서 B8
- 운영자는 **관리자 계정**으로 `/api/v1/admin/collection-runs/**`를 호출한다(`ROLE_ADMIN`, 기존 `/api/v1/admin/**` 인가 규칙)
- **불변식**: 회원당 열린 회차 1개(409), 기기는 한 번에 한 열린 회차에만 배정(409), 닫을 때 미해제 배정을 종료 시각으로 함께 해제, `data_policy=RETAIN`이면 보존 날짜 셋 필수·`identified ≤ pseudonymized ≤ research`
- **마커는 정답 라벨**이다. 판정 결과 기록(B 1.4)과 같은 자리에 섞지 않는다(정책 1.8.1). `marker_id`로 멱등이며 같은 id에 다른 내용이면 409. 누른 기기의 `clock`도 `ClockMappingService.register`로 같은 규칙으로 등록해 센서와 같은 시간축에 놓는다
- **배치 귀속**: `run_id`가 오면 그대로, 없으면 `resend.original_run_id`(늦게 온 자료를 나중 참가자에게 붙이지 않기 위해), 그것도 없으면 `resolveRun(member, device, measured_at_start)`으로 열린 회차를 찾는다. 없으면 null(운영 외 자료)
- `run_id`·`assignment_id`는 서버 발급(`run-`/`asg-` + UUID hex). 사람이 읽는 회차 번호는 `protocol_ref`
- 인시던트(본인확인·SOS·사후 판정)는 이 도메인이 아니라 별도 LLD다

### `location` — 실시간 위치
- `realtime`(WebSocket), `parentlocation`(REST). 시니어 발신 → family 검증 → 보호자 `/topic/location/{seniorId}` 구독
- 위치 이력 Redis 저장. → LLD-0001, ADR-0007

### `mypage`
- 시니어/보호자 분리(`SeniorMyPageService`·`GuardianMyPageService`·`MyPageProfileService`). Query/Command 분리 → LLD-0018

## Redis 임시 데이터

쿼리 캐시가 아닌 **TTL 기반 임시 저장**. `@RedisHash` + `@TimeToLive`로 자동 만료.

| 키 | 용도 |
|----|------|
| `RefreshToken` | JWT 리프레시 토큰 |
| `VerificationCode` | SMS 인증코드 (단기 TTL) |
| `TemporaryMember` | 회원가입 임시 상태 (30분) |
| `OAuthState` | OAuth CSRF 방지 |
| `SeniorLocation` | 실시간 위치 + 이력 |

## 설정 · 외부 연동

- **Profile**: `application.yml`에 그룹(local/dev/test) 정의, 관심사별 `application-*.yml` 분리(datasource·security·oauth·fcm·pay·redis·s3·coolsms·video·medicine·actuator).
- **외부 연동**: Firebase FCM, AWS S3(버킷 정책 접근제어, `PUBLIC_READ` ACL 미사용), Apple/Naver/Kakao OAuth, TossPayments, Coolsms(SMS), FFmpeg, 공공 약품 API, AI Heart Rate(Flask).
- **엔티티/영속성**: `@Entity`는 `widyu-domain`, `BaseTimeEntity`로 `createdAt`/`updatedAt` 자동. 테스트는 H2 in-memory(`application-test.yml`).

## 백엔드 코딩 주의사항

- **Swagger**: `controller/docs/`에 별도 `Docs` 인터페이스로 문서화 — 컨트롤러 본문은 깔끔하게 유지.
- **`@Async` 비동기**:
  - `@Async` 메서드는 **별도 빈**에 배치 (self-invocation 프록시 우회 방지)
  - `MultipartFile`은 요청 종료 후 삭제되므로 `File`로 변환해 async 스레드에 전달
  - 새 스레드는 호출자 트랜잭션을 전파받지 못하므로, DB 작업이 있는 `@Async` 메서드에는 `@Transactional`을 직접 선언한다 (훅이 누락 시 경고)
  - 임시 파일은 `finally`에서 삭제
- **MySQL ENUM**: `ddl-auto: update`는 기존 ENUM 컬럼에 새 값을 추가하지 않음 → 수동 실행 필요:
  `ALTER TABLE <table> MODIFY COLUMN <col> ENUM('A','B','NEW') NOT NULL`

## 테스트 작성 규칙

**프레임워크**: JUnit 5 + Mockito (`@ExtendWith(MockitoExtension.class)`), H2 in-memory (`application-test.yml`).

1. **DAMP > DRY** — `@BeforeEach`로 상태 공유 금지. 반복 객체 생성은 Fixture 클래스로 분리해 각 테스트를 독립적으로 유지한다.
2. **결과를 검증한다** — `verify(...)` 같은 구현 호출이 아니라 상태 변화를 검증 (`assertEquals(Status.PASS, applicant.getStatus())`).
3. **AAA 패턴** — `// given / when / then` 주석으로 구분한다.
4. **명세에 비즈니스 행위를 담는다** — 메서드명은 한글 언더스코어(`관리자_정보로_가입한다`), `@DisplayName`은 `<행위>하면 <결과>한다/반환한다/예외가 발생한다` 형식. "성공·실패·테스트" 접미사 금지.
5. **BDDMockito** — `given(...).willReturn(...)` 사용 (`when/thenReturn` 금지).
6. **예외 테스트** — `assertThatThrownBy(() -> ...).isInstanceOf(BusinessException.class)`.

**테스트 구분**: Unit(도메인 모델·비즈니스 로직) / Integration(주요 흐름·DB 등 외부 의존성) / E2E(사용자 흐름 전체).
