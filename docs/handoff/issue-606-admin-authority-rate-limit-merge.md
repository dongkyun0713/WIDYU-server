# #606 관리자 현재 권한 검증 결합 핸드오프

## 상태

`feature/606`에서 원격 `feature/605`의 최신 head `af0f266`를 merge commit `7e52068`으로 병합하고 origin에 일반 push했다. #603의 전체 세션 폐기 변경은 포함하지 않는다.

## 결합 결정

- `AdminAuthService.login`은 입력 길이 검증, IP/계정 Redis 예약, 더미 BCrypt 검증을 유지한다.
- 비밀번호·계정·현재 관리자 권한 또는 ACTIVE 상태 검증에 실패하면 같은 예약을 `completeLogin(..., false)`로 완료한다.
- 성공한 활성 ADMIN만 자신의 예약을 해제하고 토큰을 발급한다.
- refresh와 ADMIN claim 요청은 `AdminAccessValidator`가 DB의 `ADMIN && ACTIVE` 상태를 확인한다.

## 문서와 검증

- LLD: `LLD-0031-admin-current-authority.md`, `LLD-0034-auth-request-limits.md`
- 선택 로컬 실증 기록: Git 제외 `apiDocs/engineering/security/issue-606-auth-limits.md`
- 집중 회귀: `AdminAuthServiceTest`, `AdminLoginLimitRedisTest`, `AdminCurrentAuthoritySecurityTest`

## PR 순서

PR #610을 먼저 merge한 뒤 PR #612를 merge한다. #612는 #610을 병합해 두 보안 경계를 함께 검증한 상태이므로 #610보다 먼저 merge하면 안 된다.

## 완료 기록

- `AdminAuthServiceTest`, `AdminLoginLimitRedisTest`, `AdminCurrentAuthoritySecurityTest` 결합 회귀 40개가 통과했다.
- `bash scripts/harness/verify.sh`가 정적 규칙, compileJava, API 전체 테스트와 함께 통과했다.
- PR #612 본문에 #610 선행 의존과 `#610 merge → #612 merge` 권장 순서를 반영했다.

## 남은 조건

- GitHub PR merge와 배포는 이번 작업 범위 밖이다.
