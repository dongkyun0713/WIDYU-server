# LLD-0057: 관리자 접속기록 (L3)

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #666 |
| 관련 ADR | ADR-0036 결정 5·6 (feature/664에 있음. 이 브랜치에는 ADR을 복사하지 않고 PR 본문에서 링크한다) |
| 작성자 | Claude |
| 작성일 | 2026-09-21 |
| 선행 | 없음. base upstream/develop f454832 |

## 1. 목적 / 배경

관리자가 개인정보를 조회·수정·삭제한 기록을 남기고 2년 이상 보관한다(고시 제8조①2호). 지금은 쓰기 5종의 감사 로그만 있고 조회는 없다.

## 2. 범위

### In scope
- widyu-api / widyu-domain, 패키지 `com.widyu.admin` 안에 `access` 하위
- `admin_access_log` 테이블·엔티티
- `AdminAccessLogInterceptor` + 저장소 첫 `WebMvcConfigurer`
- 관리자 조회 API

### Out of scope
- 요청·응답 본문 기록, 쿼리 파라미터 값 기록, 기존 `admin_audit_log` 변경, 삭제·보관 상한, 관리자 로그인 실패 기록

## 3. 인터페이스 / API

| 메서드·경로 | 요청 | 응답 |
| --- | --- | --- |
| `GET /api/v1/admin/access-logs?adminId=&from=&to=&page=&size=` | 모두 선택. `from`/`to`는 ISO date-time | `Page<AdminAccessLogResponse>` 최근순 |

`AdminAccessLogResponse`: `id, adminId, adminName, method, path, query, targetMemberId, targetRef, status, clientIp, userAgent, accessedAt`.

## 4. 데이터 모델

`admin_access_log` (엔티티 `AdminAccessLog`):

| 컬럼 | 타입 | 제약 | 비고 |
| --- | --- | --- | --- |
| `admin_access_log_id` | BIGINT PK | | |
| `admin_id` | BIGINT | NOT NULL | 인증 없으면 `-1`(로그인 API) |
| `admin_name` | VARCHAR(50) | NOT NULL | `AdminAuditLogService`의 이름 해석과 같은 방식 |
| `method` | VARCHAR(8) | NOT NULL | |
| `path` | VARCHAR(255) | NOT NULL | `request.getRequestURI()` |
| `query` | VARCHAR(500) | NULL | 쿼리 파라미터 이름만 `&`로 연결. 값은 저장하지 않고 500자 초과는 자른다 |
| `target_member_id` | BIGINT | NULL | 경로 변수 `memberId`가 있으면 |
| `target_ref` | VARCHAR(64) | NULL | 경로 변수 `runId`·`participationId`·`exportId` 중 하나 |
| `status` | INT | NOT NULL | 응답 상태 |
| `client_ip` | VARCHAR(45) | NULL | `X-Forwarded-For` 첫 값, 없으면 remoteAddr |
| `user_agent` | VARCHAR(200) | NULL | 잘라서 |
| `accessed_at` | DATETIME(6) | NOT NULL | 요청 시작 시각 |

인덱스 `(admin_id, accessed_at)`, `(accessed_at)`. 추가 전용.

## 5. 처리 흐름

1. `AdminAccessLogInterceptor implements HandlerInterceptor`: `preHandle`에서 시작 시각을 request attribute에 두고, `afterCompletion`에서 `AdminAccessLogService.record(...)`(`@Transactional(propagation=REQUIRES_NEW)`) 호출. 예외는 삼키고 WARN(예외 클래스명·path).
2. `WebMvcConfig implements WebMvcConfigurer` (`global/config`): `addInterceptors`로 `/api/v1/admin/**`, `/api/v1/auth/admin/**` 등록.
3. 관리자 식별: `SecurityContextHolder` → `PrincipalDetails.getMemberId()`; 없으면 `-1`, 이름 `"인증 전"`. 이름 해석은 `AdminAuditLogService`의 `resolveAdminName`을 공용 헬퍼로 뽑아 둘 다 쓴다.
4. 경로 변수: `request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE)`에서 `memberId`(Long 변환 실패 시 null), `runId`/`participationId`/`exportId`.
5. 본문·헤더(Authorization 포함)는 읽지 않는다. URL 쿼리는 `name`·`q` 등에 회원 이름이나 전화번호가 들어갈 수 있으므로 값은 버리고 파라미터 이름만 중복 제거해 남긴다.
6. `GET /api/v1/admin/access-logs` 자기 자신도 기록된다(예외 두지 않는다).

## 6. 예외 / 에러 처리

새 ErrorCode 없음. 기록 실패는 요청에 영향 없음.

## 7. 인수조건 (Acceptance Criteria)

- [ ] `GET /api/v1/admin/members/{memberId}` 호출 뒤 행 1개: `admin_id`·`method=GET`·`path`·`target_member_id`·`status=200`·`accessed_at`. (`@WebMvcTest` 또는 인터셉터 단위 테스트 + `MockHttpServletRequest`)
- [ ] `POST /api/v1/auth/admin/login`(인증 전)도 `admin_id=-1`로 기록된다.
- [ ] 저장 서비스가 예외를 던져도 응답은 정상이다.
- [ ] 행에 요청 본문·Authorization 값·검색어 등 쿼리 값이 없고 쿼리 파라미터 이름만 남는다.
- [ ] 조회 API가 adminId·기간 필터와 페이지를 지원하고 최근순이다.
- [ ] `./gradlew compileJava`, `run-module-tests.sh`, `verify.sh --base` 통과.

## 8. 영향 범위 / 마이그레이션

`scripts/mysql/create_admin_access_log.sql`. ERD. `backend/CLAUDE.md` admin 절에 접속기록 한 줄. 보관: 삭제 스케줄러 없음(2년 최소 보관).

## 9. 미결정 사항 (Open Questions)

- 없음.

## 10. 참고
`AdminAuditLogService`(관리자 식별·이름 해석), `SecurityConfig`(`/api/v1/admin/**` ROLE_ADMIN), `AdminAuditLogController`(조회 API 형식), `WebSocketConfig`(설정 클래스 위치 선례).
