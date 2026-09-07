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
- **불변식**: 시니어 가입 시 100포인트 지급, 앨범 잠금해제 50포인트 차감. TossPayments 연동
- 결제 트랜잭션 경계·멱등성은 → LLD-0003·0015·0016·0017, ADR-0012

### `goal` — 건강 목표
- `medicine`(복약 스케줄·알람 인증), `walk`(`SeniorProfile.defaultWalkGoal`), `healthschedule`, `addressbookmark`, `home`(가족 멤버 조회)
- **복약 불변식**: 알람 ±30분 이내에만 인증 제출, 같은 날 동일 스케줄 중복 불가, 월별 준수율 통계. → LLD-0007·0008·0009

### `heart` — 심박수 (독립 도메인)
- 심박 15개 값 배치 → AI(`POST /api/hr`) 단건 15회 순차 → `level` NORMAL/CAUTION/EMERGENCY 중 배치 최댓값
- `alert=true`인 EMERGENCY 시 `HeartRateEmergency` 기록 + 보호자 FCM
- **AI 응답 중 `alert`·`level`만 사용**. `reason`(서맥·빈맥 등 사유)·`layer`(L0 고정임계값/L1 개인기준선)·`baseline_source`는 폐기 — 개인화는 `context=REST`에서만 동작
- REST(`HeartRateController`) + WebSocket(`HeartRateWebSocketController`) 이중 지원
  - 시니어 전송: `/app/heart-rate/send-single`(1건, 권장) 또는 `/app/heart-rate/send`(15개 배치, 전환기 유지)
  - 보호자 구독 `/topic/heart-rate/{memberId}` / ACK `/user/queue/heart-rate/result`
  - **단건 경로가 기본**. 배치는 최신값이 배치 주기(약 15초)마다만 갱신돼 조회 지연을 유발한다 → ADR-0017, LLD-0023
- **위급 사이클**: 위험 감지 후 5분 유지, 그 안에 재감지되면 마지막 감지 기준 연장. "마지막 감지 + 5분" == "최근 5분 내 감지 존재"이므로 상태를 저장하지 않고 조회 윈도우로만 판정. **그래프 조회 범위 산정에만 사용**하며, `/emergency/recent`는 사이클과 무관하게 최근 15분 내 기록 유무만 반환
- **조회 범위 규칙**: 그래프는 응급 화면이므로 events·max·min·firstEmergency가 **진행 중인 사이클 시작 5분 전 ~ 현재**. 사이클 없으면 빈 배열. `emergencyHistory`의 count·events는 전체 기간이지만 `totalDuration`은 현재 사이클 지속 시간(첫 감지~마지막 감지, 분). 갱신(`/graph/refresh`)은 `since` 지정 시 그 이후 신규 이벤트만, 미지정 시 최근 5개
- 조회 지연은 정상 — 배치 저장 시점에만 갱신됨(워치 배치 주기 + AI 15회 순차 호출)
- AI: Docker `ryuchanghoon/widyu-ai-ver7:latest` port 5000, multi-arch. → LLD-0010·0019·0020, ADR-0008·0013·0014

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
