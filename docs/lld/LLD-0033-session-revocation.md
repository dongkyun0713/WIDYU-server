# LLD-0033: 전체 기기 세션 폐기

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #603 |
| 관련 ADR | ADR-0002, ADR-0020 |
| 작성자 | Codex |
| 작성일 | 2026-09-14 |

## 1. 목적 / 배경

Refresh 삭제만으로는 이미 발급한 access token과 연결된 WebSocket을 폐기할 수 없다. DB 회원의 인증 버전을 단조 증가시키고 사용 시 현재 버전과 ACTIVE 상태를 검증해 Redis eviction 이후에도 폐기를 유지한다.

## 2. 범위

widyu-domain Member, widyu-api 토큰 발급·검증, 로그아웃·정지·탈퇴·비밀번호 변경, WebSocket 입출력과 연결 수명을 변경한다. #602 목적지 allowlist와 #605 관리자 권한 검증을 유지한다. #606 rate limit, push·PR·운영·AWS 호출은 제외한다. 기존 JWT 인증을 보완하므로 신규 ADR은 N/A이며 대안과 트랜잭션 계약을 이 문서에 기록한다.

## 3. 인터페이스 / API

기존 로그인·refresh 응답과 로그아웃·탈퇴·비밀번호 변경 요청 형식을 유지한다. 로그아웃은 해당 회원의 모든 기기를 폐기한다. JWT access/refresh에 정수 `authVersion` claim을 추가한다. 30초 일회용 ws-token의 Redis 값에 회원 ID와 버전을 저장한다.

구버전 access/refresh(버전 claim 누락)와 ws-token(회원 ID만 저장)은 거절한다. 배포 후 기존 클라이언트는 재로그인해야 한다. 버전 0으로 간주하는 호환은 폐기 우회 위험 때문에 제공하지 않는다. 만료 access만으로 재발급하는 메서드는 재발급하지 않는다.

기존 회원의 소셜 임시 JWT에도 `authVersion`을 넣는다. 계정 연동 시 잠금 아래 ACTIVE·버전을 검증하고 성공 시 버전을 증가시켜 해당 JWT와 이전 세션을 폐기한다. 연동 응답은 새 버전의 토큰이다. SMS 본인확인 시 기존 회원이면 Redis TemporaryMember에 `memberId`, `authVersion`을 저장한다. 비밀번호 재설정과 기존 회원에 로컬 계정 추가는 같은 잠금 아래 이 값을 검증한다. 이전 버전 또는 회원 연결 정보가 없는 구형 임시 데이터로 기존 계정을 변경할 수 없으며 SMS 확인을 다시 해야 한다. 신규 회원의 가입용 임시 데이터는 회원 버전을 요구하지 않는다.

기존 회원의 로컬 계정 연동은 TemporaryMember.memberId를 고정 식별자로 사용한다. 해당 회원이 탈퇴·삭제되거나 버전이 바뀌면 거절하고 이름/전화번호 조회를 통한 새 Member 생성으로 전환하지 않는다. 연동 성공 시 같은 Member 잠금 안에서 authVersion을 증가시켜 1회 소비한 다음 증가된 버전으로 토큰을 발급한다. Redis 삭제 전에 같은 임시 데이터를 읽은 서로 다른 이메일의 동시 요청도 한 요청만 성공한다. 새 회원용 임시 데이터의 반복 가입 제어는 이번 명시 범위에서 제외한다.

## 4. 데이터 모델

Member.authVersion: BIGINT NOT NULL DEFAULT 0. Member.reactivationBlocked: BOOLEAN NOT NULL DEFAULT FALSE. 정지는 INACTIVE, 탈퇴는 DELETED를 사용한다. 과거 INACTIVE는 정지·탈퇴가 혼재하므로 전부 `reactivation_blocked = TRUE`로 이관한다. 이름이나 전화번호의 마스킹 여부로 자동 분류하지 않는다. 미분류 회원은 로그인, 관리자 재활성화와 정지 재설정이 모두 거절된다. 별도 근거로 정지가 확인된 회원 ID만 운영자가 차단을 해제할 수 있으며, 탈퇴가 확인되면 DELETED로 분류하고 차단을 유지한다. 이번 작업은 운영 분류나 SQL 실행을 포함하지 않는다.

## 5. 처리 흐름

1. 발급과 refresh 검증은 Member 행의 PESSIMISTIC_WRITE 잠금을 얻는다. 상위 서비스도 쓰기 트랜잭션을 유지한다. 첫 잠금에서는 신규 가입의 미flush 필드·cascade 관계를 flush한 뒤 Member를 refresh한다. 동일 트랜잭션에서 이미 잠갔으면 refresh를 반복하지 않아 이후 수정이 소실되지 않는다. 로컬·관리자 로그인은 Member 다음 LocalAccount를 PESSIMISTIC_WRITE로 조회하고 LocalAccount 자체도 같은 모드로 refresh한 뒤 비밀번호를 검증한다. MySQL REPEATABLE_READ에서는 Member의 cascade refresh만으로 계정 current-read를 보장하지 않는다. 비밀번호 변경도 Member → LocalAccount 순서를 지키며, 검증부터 발급/변경 커밋까지 잠금을 유지한다.
2. 폐기 경로도 동일 행을 잠근 뒤 버전을 증가시킨다. DB 커밋이 폐기의 기준이며 Redis refresh 삭제는 보안의 근거가 아니다.
3. REST access 사용은 현재 DB 상태·버전을 조회한다. 캐시로 대체하지 않는다. 관리자 권한은 #605 검증을 추가로 유지한다.
4. WS handshake에서 검증한 버전을 세션에 저장하고 CONNECT/SEND/SUBSCRIBE 및 outbound 전달 시 재검증한다. WsSessionGuard.preSend는 로컬 세션 존재만 확인하고, DB 상태·버전·가족 관계는 beforeHandle에서 각 handler 실행 직전에 검사한다. 동기 executor도 ExecutorSubscribableChannel의 beforeHandle을 거친다. 큐 대기 중 폐기·가족 해제 및 DB 장애 시 전달하지 않는다. 세션·가족 인가 결과는 캐시하지 않는다.
5. 폐기 커밋 후 로컬 연결을 종료하고 주기적 검사로 다른 인스턴스의 유휴 연결도 종료한다. outbound은 각 전달 시 DB를 검증하므로 종료 대기 동안에도 폐기된 데이터는 전달하지 않는다. 가족 해제 역시 기존 구독에 전달하지 않고 연결을 종료한다.
6. 로컬·소셜 로그인은 INACTIVE/DELETED를 자동 reactivate하지 않는다. 기존 소셜 회원은 잠금 후 ACTIVE와 provider/oauthId를 다시 확인한다. 탈퇴 시 외부 계정 해제도 같은 Member 잠금 아래 수행한다. 관리자 재활성화는 DELETED와 미분류 과거 INACTIVE를 복구하지 않는다.

## 6. 예외 / 에러 처리

버전 누락·불일치와 비활성 회원은 기존 INVALID_ACCESS_TOKEN/INVALID_REFRESH_TOKEN 또는 FORBIDDEN으로 거절한다. DB/Redis 장애 시 인증·전달을 허용하지 않는다. 폐기 DB 트랜잭션이 rollback되면 폐기 성공으로 보고하지 않는다.

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1: access/refresh/ws-token 버전과 ACTIVE 검증, 구버전 거절.
- [x] AC2: 로그아웃·정지·탈퇴·비밀번호 변경 후 모든 기존 토큰 거절, Redis eviction 후에도 유지.
- [x] AC3: refresh/폐기 DB lock 경합에서 폐기 이전 버전이 부활하지 않음.
- [x] AC4: INACTIVE와 DELETED 구분, 로컬·소셜 로그인 및 관리자 재활성화 우회 차단.
- [x] AC5: 기존 WS 세션 폐기 및 가족 해제 후 기존 구독/outbound 전달 차단 회귀.
- [x] AC6: 만료 access 단독 재발급 차단, 기존 관리자/임시 토큰/목적지 회귀 유지.
- [x] AC7: ERD·SQL·Swagger 반영, meaningful 회귀·compileJava·하네스 검증 및 자체 review.
- [x] AC8: 로컬·관리자 계정 선조회 → 별도 트랜잭션 비밀번호 변경 커밋 → 로그인 잠금·재검증 순서에서 옛 비밀번호 거절. 검증 우선 경합에서는 변경이 대기한 뒤 발급 토큰 폐기.
- [x] AC9: 소셜 임시 토큰의 실제 계정 연동·소비·폐기 후 재사용 거절. 비밀번호 재설정 TemporaryMember가 Redis에 남거나 복원돼도 폐기·성공 사용 이후 재사용 거절.
- [x] AC10: 신규 가입의 미flush 필드·관계와 재잠금 사이 수정 보존. 과거 INACTIVE 재활성화 차단 SQL·회귀 포함.
- [x] AC11: 기존회원 로컬 계정 연동의 다른 이메일 동시 요청은 하나만 소비하고, 새 access/refresh는 증가된 버전으로 유효함. 탈퇴·삭제 후 신규 가입 fallback 금지. H2·MySQL 실제 트랜잭션 회귀.

2026-09-14 검증: 전체 하네스에서 정적 검사·compileJava·Domain 28개·API 671개 통과, 영상 fixture 부재 3개 skip(MySQL 24개 포함). 그 이후 AC11 보완은 사용자 지시에 따라 영향 회귀 68개(H2·MySQL 각각 27개 포함)와 정적 검사만 추가 실행해 통과했다. 보완 후 전체 통합 재검증은 root 예정이며, 이전 전체 하네스 결과와 구분한다. 자체 review는 인수조건·잠금 순서·임시인증 소비·마이그레이션·WS 실패 동작을 확인하여 APPROVE. 운영 승인이나 RDS 성능 검증을 의미하지 않는다.

## 8. 영향 범위 / 마이그레이션

통합 후 #606 요청 제한을 먼저 예약하고 Member → LocalAccount 잠금 아래 비밀번호를 검증하도록 결합했다. SMS는 Lua 원자 소비 후 기존 회원 버전을 저장한다. 2026-09-14 `feature/604-integration` 최종 전체 하네스는 API 817건·Domain 28건 모두 실패/skip 없이 통과했다. API에는 실제 MySQL 세션 경합 27건과 FCM 회귀 14건이 포함된다. 별도 결제 MySQL 6건도 통과했다. 자체 통합 review는 충돌 해결에서 권한·버전·제한을 모두 보존했음을 확인했다. 운영 알림·복원·배포 승인은 포함하지 않는다.

`scripts/mysql/add_member_auth_version.sql`을 애플리케이션 전환 전에 적용한다. 구 서버를 중지하고 새 버전으로 전환해야 하며 구 서버와의 혼합 운영은 폐기를 보장하지 않는다. Redis blacklist는 eviction 시 폐기가 사라져 제외했다. DB 버전 조회·잠금은 DB 부하와 동일 회원 발급 직렬화 비용이 있다. 이미 인가되어 실행 중인 REST 요청/네트워크 전송은 소급 취소하지 않는다.

WS 유휴 검사 간격은 fixedDelay 1초다. 실제 종료 지연은 검사 시간과 스케줄 지연을 더하므로 1초 이내를 보장하지 않는다. 50세션·20Hz 전체 fanout이면 초당 1,000개 outbound 전달이며 버전 검사는 1,000 + poll 50 ≈ 1,050회/초 모델이다. 가족 토픽은 전달마다 FamilyAccessService의 별도 SQL이 추가되며 지연 로딩에 따라 실제 쿼리 수가 달라진다. 연결·인바운드·업무 SQL도 별도다. 실제 RDS 부하와 개선율은 측정하지 않았다. 자세한 목표·조회 모델·실행 결과는 Git 제외 `apiDocs/api/auth/session-revocation-measurement-record.md`에 기록한다.

## 9. 미결정 사항 (Open Questions)

- [ ] 구버전 토큰 거절에 따른 전체 재로그인 안내·전환 시각·구 서버 중지 절차를 기획/운영에서 확인해야 한다. 보안 구현은 구버전을 거절하지만 이 문서가 실제 배포 승인을 의미하지 않는다.
- [ ] 과거 INACTIVE 회원의 정지/탈퇴 분류 담당자와 근거를 정해야 한다. 자동 재활성화나 운영 데이터 분류는 실행하지 않았다.

## 10. 참고

- [ERD](../erd/ERD-0001-initial-domain.md)
- [관리자 검증](LLD-0031-admin-current-authority.md), [WS 목적지](LLD-0032-websocket-destination-allowlist.md)
