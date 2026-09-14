# LLD-0034: 인증·로그인 제한과 민감 로그 정리

| 항목 | 값 |
| --- | --- |
| 상태 | Review — 인증 초기값 사용자 승인, 전체 문자 한도 입력·운영 검증 별도 |
| Issue | #606 |
| 관련 ADR | ADR-0002, ADR-0025 |
| 작성자 | Codex |
| 작성일 | 2026-09-14 |

## 1. 목적 / 배경
문자 발송·비밀번호 대입을 제한하고 인증코드의 동시 중복 소비를 막는다. 인증 요청·응답·공급자 오류의 민감값을 로그에서 제거한다.

## 2. 범위
widyu-api의 SmsService, VerificationCodeService, 일반 LocalLoginService와 관련 저장소·설정·DTO·controller·test 및 인증 로그. widyu-domain은 ErrorCode 추가만 수행한다.
추가 승인으로 AdminAuthService 로그인 제한, 운영 compose Redis 정책, CI 격리 Redis 실행 파일 준비를 포함한다. AdminAuthService의 기존 권한 검사와 refresh는 보존하며 #605의 DB 권한·활성 검증은 별도 변경이다. JwtAuthenticationFilter, JwtTokenProvider는 계속 수정하지 않는다. 커밋·push·PR·AWS 변경·배포는 범위 밖이다.
ADR 추가 N/A: 기존 Redis TTL·일회 소비 원칙을 인증 기능 내부에 적용하며 알고리즘은 이 문서에서 관리한다.

## 3. 인터페이스 / API
기존 POST /api/v1/auth/sms/send, /api/v1/auth/sms/send-if-member-exist, /api/v1/auth/sms/verify, /api/v1/auth/guardians/sign-in/local 성공 계약을 유지한다.
한도 초과는 429 AUTH_4290과 Retry-After 초 단위 헤더, 기존 ApiResponseTemplate 오류 구조를 반환한다. Redis 장애·전체 문자 예산 미설정은 503 AUTH_5030이다. 일반 로그인은 없는 계정과 오비밀번호에 동일한 401 AUTH_4012를 반환한다. 관리자 로그인은 기존 INVALID_EMAIL/INVALID_PASSWORD 구분을 유지하므로 더미 BCrypt와 요청 제한이 있어도 응답으로 계정 존재를 구별할 수 있다.
Naver 테스트 컨트롤러 /api/v1/auth/test/naver/**를 삭제한다.

## 4. 데이터 모델
ERD-0001의 Member–LocalAccount 관계를 유지하며 JPA·ERD 변경은 없다. 새 Redis 키는 auth:{limits}: 접두사와 TTL을 사용한다. 동일 hash tag로 다중 키 Lua를 원자 실행한다. 식별자 키는 SHA-256 요약값이며 암호화·익명화가 아니다.
- 문자: 번호 간격·시간·일, IP 시간, 전체 일 카운터를 함께 검사·증가·TTL 설정한다. 첫 허용 요청부터 고정 TTL 창이다.
- 코드: 기존 RedisRepository와 별도 v2 hash에 코드·이름·실패횟수를 저장한다. 검증·실패 증가·5회 차단·성공 삭제는 한 Lua다.
- 로그인: 계정·IP sorted set에 고유 요청 ID와 만료시각을 저장한다. 실제 계정은 LocalAccount ID로 묶어 DB collation에 따른 이메일 별칭 우회를 막는다. 없는 계정은 소문자·앞뒤 공백 제거 이메일로 묶는다. 실패와 진행 중 요청을 함께 세고 성공은 자기 예약만 제거한다. 실패는 완료부터 창을 유지한다. 만료된 예약은 토큰 발급을 허용하지 않는다.

## 5. 처리 흐름
1. 문자 입력 검증 → 직접 연결 IP → 모든 카운터 예약 → 코드 저장 → 외부 발송. Redis 실패면 공급자를 호출하지 않는다. 공급자 실패에도 예약을 돌려주지 않으며 자기 코드만 조건부 삭제한다.
2. 코드 일회 소비와 이름 반환 → 임시 회원 저장 → 토큰 발급. 후속 실패에도 코드를 복구하지 않아 재발송이 필요하다.
3. 로그인 입력 검증 → 직접 연결 IP·계정 조회 → 계정·IP 예약 → 비밀번호 검증 → 예약 완료 → 토큰 발급. 없는 계정도 더미 비밀번호를 검증한다. 예외·프로세스 종료 시 예약은 TTL까지 실패처럼 집계한다. 계정 조회 자체는 제한 전 실행되므로 DB 조회 부하 방어는 별도다.
4. Redis 상태는 JPA 롤백으로 복구하지 않는다. 기존 서비스 구조와 별도 인프라 저장소를 사용하며 이벤트는 추가하지 않는다.
5. Tomcat 첫 engine valve가 forwarding 이전 peer와 X-Forwarded-For 전체 행을 서버 속성으로 보존한다. native/framework forwarding을 이유로 시작을 거부하지 않는다. ClientIpResolver는 보존된 peer가 `auth.proxy.trusted-cidrs`에 포함될 때만 헤더를 오른쪽부터 검사하고 신뢰 프록시를 제거한 첫 비신뢰 IP를 선택한다. 기본 목록은 비어 있다. 검사 중 잘못된 IP·빈 항목을 만나거나 모든 IP가 신뢰 대상이면 peer를 사용한다. 첫 비신뢰 IP 왼쪽의 위조 입력은 무시한다. IP는 숫자 리터럴만 허용하며 IPv4/IPv6를 정규화한다. Forwarded/X-Real-IP는 사용하지 않는다. 보존 속성이 없으면 해당 인증 요청을 503으로 차단한다.
6. 관리자 로그인도 같은 LocalAccount ID/없는 계정 이메일 및 IP 예약을 공유한다. LocalLogin과 같이 null·blank 및 이메일 254자/비밀번호 256자 초과를 조회·예약·BCrypt 전에 거부한다. 이메일 범위 오류는 INVALID_EMAIL, 비밀번호 범위 오류는 INVALID_PASSWORD다. 유효 범위의 없는 계정은 더미 비밀번호를 검사한다. 기존 INVALID_EMAIL/INVALID_PASSWORD/FORBIDDEN 계약을 유지하고 이미 생성된 예약의 인증 실패는 completeLogin(false)로 완료한다. 관리자 권한 검사 통과 후 자기 예약만 해제한다. 일반 로그인에서 남긴 실패도 관리자 성공으로 삭제되지 않는다.

아래 auth.limits 초기값과 코드 오입력 5회는 **2026-09-14 사용자 대화에서 승인**됐다. 실제 구현값이 일치하며 운영 배포 승인은 별도다. 전체 문자 일일 한도는 기본값 없이 운영 배포 전에 입력한다.

| 속성 | 초기값 |
| --- | --- |
| sms-interval-seconds | 60 |
| sms-phone-hour | 3 |
| sms-phone-day | 10 |
| sms-ip-hour | 30 |
| sms-global-day | 미설정 — 발송 차단, 운영 양의 정수 입력 필요 |
| login-window-seconds | 900 |
| login-account-failures | 10 |
| login-ip-failures | 100 |

코드 오입력 한도는 5회다. coolsms 코드 길이는 DTO 계약과 같은 6, TTL은 양수여야 한다.

## 6. 예외 / 에러 처리
Redis null·접속·스크립트 장애는 원인 메시지 없는 AUTH_5030으로 변환한다.
429 Retry-After는 차단된 창의 최대 잔여 TTL 올림값이다. 다섯 번째 코드 오입력부터 TTL까지 올바른 코드도 429다.
없는/소비된 코드는 SMS_4040, 1~4회 오입력은 SMS_4000이다. 공급자 예외·실패목록·요청/응답 본문·토큰을 로그와 오류 응답에 넣지 않는다.
일반 인증 경로의 처리 예외는 원문을 로그·오류 응답에 넣지 않으며 debug 상세 응답도 비활성화한다. 수정 제외된 JwtTokenProvider 내부 로그는 별도 작업이 해결해야 한다.

## 7. 인수조건 (Acceptance Criteria)
- [x] AC1: 문자 카운터 경계·TTL·동시 요청을 실제 로컬 Redis로 검증한다.
- [x] AC2: Redis 장애·저장 실패·전체 예산 미설정에서 공급자를 호출하지 않는다.
- [x] AC3: 코드 오입력 5회 차단·만료·동시 일회 소비·재발송을 검증한다.
- [x] AC4: 로그인 실패·성공의 자기 예약 해제·동시 요청·예약 만료를 검증한다.
- [x] AC5: 빈 신뢰 목록·위조 헤더·잘못된 IP·IPv6·다중 프록시를 검증하고 실제 Tomcat none/native/framework 환경에서 원래 peer 보존을 검증한다.
- [x] AC6: 429/Retry-After·503·DTO·Naver 경로 제거·합성 민감 로그 회귀를 검증한다.
- [x] AC7: Swagger 예외를 반영하고 API 테스트·harness verify 결과를 기록한다.
- [x] AC8: 관리자 실패/권한 거부·성공 자기 예약 해제·공유 계정/IP 제한·Redis 장애를 격리 Redis로 검증한다.
- [x] AC9: 운영 override에서 noeviction을 사용하고 실제 격리 Redis OOM에서 한도 키·TTL 보존과 SMS 503/외부 발송 없음, 용량 복구 후 예약을 검증한다.
- [x] AC10: CI가 host Redis 서비스를 시작하지 않고 redis-server 바이너리와 실행 의존성을 준비하며 버전을 검사한다. GitHub 실행 결과는 미검증이다.

## 8. 영향 범위 / 마이그레이션
구 저장소 코드는 읽지 않으므로 배포 전 발송 코드는 재발송해야 한다. 구버전 혼합 운영은 제한 우회를 허용하므로 금지한다. Redis 초기화·eviction·failover 데이터 손실 시 카운터도 초기화될 수 있다. 운영 Redis 권한·메모리 정책·지속성·복제 및 공급자 예산은 별도 검증 대상이다. DB 마이그레이션은 없다.
운영 전체 예산은 AUTH_LIMITS_SMSGLOBALDAY 환경변수로 1~2147483647의 정수를 입력한다. 기본값은 없다. 운영 compose가 이 변수를 전달하며 애플리케이션 자체는 미입력 시 시작을 허용하되 SMS는 503이다. 별도의 배포 전 계약으로 validate-prod.py는 AUTH_LIMITS_SMSGLOBALDAY와 AUTH_PROXY_TRUSTEDCIDRS의 미입력·공백을 거부한다. CIDR은 쉼표로 구분한 숫자 IPv4/IPv6와 유효한 prefix여야 한다. 합성 테스트 값은 운영 권장값이나 승인값이 아니다. 나머지 auth.limits 속성도 Spring Boot 환경변수 바인딩으로 변경할 수 있다(점은 밑줄, 하이픈은 제거).
신뢰 목록은 AUTH_PROXY_TRUSTEDCIDRS에 쉼표로 구분한 IPv4/IPv6 CIDR을 입력한다. 기본은 빈 목록이다. 운영 nginx는 XFF를 `$remote_addr`로 덮어쓰고 공통 nginx는 `$proxy_add_x_forwarded_for`를 사용한다. compose bridge에는 고정 subnet/IP가 없어 실제 nginx peer를 검증하기 전 임의 사설망 전체를 신뢰하지 않는다. 상위 LB를 사용해도 현재 운영 nginx는 LB 주소만 전달하므로 그 토폴로지는 별도 검증해야 한다. 기본 빈 목록에서 프록시 IP 공유는 계속된다.
peer 보존은 embedded Tomcat engine pipeline 첫 valve라는 서버 계약이다. 뒤에서 실행되는 RemoteIpValve와 ForwardedHeaderFilter는 기존 URL/scheme forwarding을 계속 수행한다. 다른 서버나 먼저 실행되는 사용자 정의 주소 변경 valve를 도입할 때는 같은 계약을 구현·검증해야 한다.
운영 Redis command만 `--maxmemory 256mb --maxmemory-policy noeviction --appendonly yes`로 덮어쓴다. 공통/개발 compose와 Grafana는 수정하지 않는다. 메모리 고갈 시 새 쓰기가 실패하고 SMS는 fail closed한다. 공유 Redis의 refresh token/위치 등 다른 쓰기도 실패할 수 있다. 배포 전 used_memory/maxmemory, RSS/호스트 여유 메모리, OOM 오류, AUTH_5030/SMS 실패율 경보와 대응 담당자를 준비해야 한다. 경보 임계값은 운영 결정이며 이번 작업은 모니터링을 배포하지 않는다. 용량 확보나 원인 트래픽 차단으로 복구하며 제한 키 삭제/eviction 재활성화를 복구 절차로 사용하지 않는다. noeviction은 restart/failover의 데이터 유실까지 막지 않는다.
새 Redis 통합 테스트는 PATH의 redis-server 실행 파일을 요구하며 테스트마다 루프백 임시 포트·비영속 Redis를 시작하고 종료한다. API Gradle 테스트를 실행하는 ci.yml과 deploy-prod.yml은 공통 scripts/ci/prepare-redis.sh를 먼저 실행한다. redis-tools와 런타임 의존성을 설치하고 redis-server/redis-tools 패키지를 임시 경로에 추출해 버전 검사 후 PATH에 추가한다. redis-server 패키지의 서비스 설치 스크립트는 실행하지 않아 기존 CI 6379 서비스와 충돌하지 않는다.

## 9. 미결정 사항 (Open Questions)
사용자가 기술 기본값으로 구현을 승인했으므로 아래 운영 결정은 구현을 막지 않는다. 임의 확정하거나 배포하지 않는다.
- 전체 문자 일일한도 입력값(나머지 표의 초기값은 2026-09-14 승인 완료).
- 일일 창의 달력 날짜 기준 전환 여부.
- 실제 프록시 경로·신뢰 목록·공유 IP 영향.
- Redis 장애·코드 소비 후 토큰 실패의 운영 대응·경보.

## 10. 참고
#606, ADR-0002, ADR-0025, LLD-0004, ERD-0001. 선택 이유·실제 재현·검증·한계는 Git 제외 apiDocs/engineering/security/issue-606-auth-limits.md에 기록한다.

## 11. 검증 결과

### 최종 좁은 보완 검증 (2026-09-14, 아래 기존 실측 이후)

사용자 승인 범위 세 항목만 보완했다. 관리자 입력 경계, 운영 AUTH 입력 검증, CI/deploy-prod의 공통 Redis 준비가 대상이다. AdminAuthServiceTest 15개, LocalLoginServiceTest 8개, AdminLoginLimitRedisTest 5개: 총 28개 통과, 실패·건너뜀 0. 기존 오류 구분·권한 거부·실패 예약 보존을 포함한다. `python3 -m unittest discover -s scripts/docker -p 'test_*.py'` 13개, scripts/ci의 같은 회귀 명령 3개 통과. CI 준비는 가짜 패키지 도구로 추출·버전 검사·실패 전파·PATH 등록 및 두 workflow의 호출 순서를 확인했다. `bash -n scripts/ci/prepare-redis.sh`, `bash scripts/harness/verify.sh --static-only`, `git diff --check` 통과.

review 판정은 이번 세 항목에 한해 APPROVE다. 기존 API 587개 중 584개 통과·영상 3개 skip 및 전체 harness 통과 기록은 보존했으며 이번에는 전체 API/Domain/harness 테스트를 반복하지 않았다. API 전체 최종 통합 검증은 root가 수행한다. Ubuntu 실제 패키지 준비·GitHub CI/배포·AWS는 미실행이고 commit/push/PR도 수행하지 않았다. 운영 정책 기본값은 여전히 미승인이다.

병합 경계: #605의 권한·활성 검증은 이식하지 않고 현재 관리자 권한 검사와 refresh를 보존했다. #607의 Discord 검증은 현재 worktree에 아직 없으며 이 보완은 validate-prod.py의 AUTH 필수 목록·양의 정수·CIDR 검증과 해당 테스트만 추가한다. root 통합 시 #607의 Discord 필수 항목·검증·합성 fixture를 함께 보존해야 한다. 공통 compose는 미수정이며 prod compose는 기존 변경 위에 입력 계약 설명만 보완했다.

2026-09-14 로컬 검증: 인증 테스트 133개 통과(건너뜀 없음). `bash scripts/harness/verify.sh`에서 Java 정적 검사·compileJava·domain/API 전체 테스트가 통과했다. Domain 28개 통과, API 553개 중 544개 통과·9개 건너뜀·실패 0개다. 기존 JWT/WS Redis 테스트 6개는 localhost:6379 미기동, 영상 벤치마크 3개는 로컬 전제조건 미충족으로 건너뛰었다. 신규 격리 Redis 테스트는 모두 실행했다.

자체 review 판정: APPROVE(요청 범위의 구현 검수). JwtTokenProvider 83·100행의 파싱 예외 DEBUG 로그 위험은 수정 제외 범위에 남아 있으며 운영 정책·배포 승인은 별도다. 운영 SMS/OAuth 호출, 실제 프록시, Redis TLS/HA/eviction, CI는 검증하지 않았다.

### 추가 보완 검증 (같은 날, 위 기록 이후)

ClientIpResolverTest 25, AuthProxyServerTest 3, AuthLimitWebTest 6, AdminLoginLimitRedisTest 5, AuthRedisMemoryTest 1, AdminAuthServiceTest 4: 총 44개 통과, 실패/건너뜀 0. 실제 Tomcat 세 forwarding 경로, 신뢰 체인, 관리자 예약과 격리 Redis noeviction/OOM을 검증했다. 운영 compose 병합의 Redis command와 AUTH 환경변수 전달을 확인했다. 최초 jq 추출은 environment 배열을 객체로 취급해 실패했으며 배열 처리로 수정 후 종료 코드 0이었다.

`bash scripts/harness/verify.sh` 1회 종료 코드 0. Java 정적 검사·compileJava 통과, 하네스 회귀 39개·Bash guard 68개·branch guard 11개 통과. API 587개 중 584개 통과·영상 벤치마크 3개 건너뜀·실패 0. 기존 6379 시험용 Redis를 사용하는 JWT/WS 6개도 이번에는 통과했다. Domain은 UP-TO-DATE로 기존 28개 통과 결과를 재사용했다. 전체 테스트를 추가 반복하지 않았다. `git diff --check` 통과.

추가 범위 review: APPROVE. AC5/8/9/10 및 기존 관리자 권한 검사·refresh 보존, JWT 두 파일/공통·개발 compose/Grafana 변경 없음, 운영 자원 접근 없음과 신규 Redis 격리를 확인했다. #605의 별도 권한·활성 검증은 이 worktree에 이식하지 않았으며 병합 경계를 apiDocs에 남겼다. Ubuntu 패키지 설치 단계와 GitHub CI, 실제 운영 프록시·메모리 경보·HA는 미실행이다. 빈 신뢰 목록의 공유 IP 영향, noeviction의 공유 Redis 쓰기 장애 영향, 기존 JwtTokenProvider 로그 위험은 남는다. 이 결과는 한도 정책·배포 승인이 아니다.
