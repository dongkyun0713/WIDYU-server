---
name: review
description: Claude Code 또는 Codex가 작성한 WIDYU-server 변경을 LLD 인수조건과 코딩 규칙 기준으로 검수한다.
---

# review

Claude Code 또는 Codex가 작성한 코드 변경을 LLD + 코딩 규칙 기준으로 검수한다.
문제가 있으면 구체적인 파일·라인과 함께 보고한다. 없으면 PR 진행을 승인한다.

## 절차

1. `git status --short --branch`로 검수 범위를 정하고 아래 diff를 읽는다.
   - 미커밋 변경: `git diff HEAD`와 `git ls-files --others --exclude-standard`의 신규 파일을 함께 확인한다. staged 변경도 포함한다.
   - 브랜치/PR: 최신 base와 HEAD의 merge-base를 구해 `git diff <merge-base> HEAD`를 확인한다.
   - 특정 커밋: `git show <commit>`을 확인한다.
2. 변경 파일에서 관련 LLD를 추론해 `docs/lld/`에서 읽고, 관련 ERD도 `docs/erd/`에서 읽는다. LLD가 없으면 `docs/lld/README.md`의 작성 기준과 N/A 사유를 확인한다.
3. `bash scripts/harness/verify.sh`를 실행한다. 커밋된 변경을 검수할 때는 `--base <merge-base>`를 전달한다. 개별 Java 확인에는 `validate-java-rules.sh`를 사용할 수 있다.
4. 아래 검수 체크리스트를 하나씩 확인한다.
5. 결과를 보고한다.

## 검수 체크리스트

### 1. LLD 인수조건 충족
- LLD `## 7. 인수조건` 항목을 하나씩 읽고 코드에서 대응하는 구현이 있는지 확인한다.
- 미구현 항목이 있으면 목록으로 보고한다.

### 2. 코딩 규칙 (validate-java-rules.sh 결과 포함)
- [ ] 삼항 연산자(`? :`) 없음
- [ ] Service/Facade에서 `new XxxResponse(` 직접 생성 없음 — `from()`/`of()` 사용 여부
- [ ] Controller에서 Repository 직접 import 없음
- [ ] `widyu-api`에 `@Entity` 없음
- [ ] `widyu-domain`에 Repository 없음
- [ ] `@Async` 메서드에 `@Transactional` 없는 경우 경고

### 3. 모듈 배치
- [ ] 신규 엔티티는 `widyu-domain`에 위치
- [ ] 신규 Repository/Service/Controller는 `widyu-api`에 위치

### 4. 테스트
- [ ] LLD 인수조건 항목에 대응하는 테스트 메서드가 존재하는가
- [ ] 테스트가 `@ExtendWith(MockitoExtension.class)` + BDDMockito 패턴인가
- [ ] 테스트 메서드명이 한글 언더스코어 형식인가

### 5. 엔티티 변경 (해당 시)
- [ ] `docs/erd/`의 ERD 문서가 변경 사항을 반영하고 있는가
- [ ] MySQL ENUM 변경이 있으면 PR 본문 비고에 ALTER TABLE 명시 여부

### 6. Swagger (해당 시)
- [ ] 신규/변경 API에 `controller/docs/`의 `*Docs` 인터페이스가 업데이트됐는가

### 7. 고위험 경로와 실패 동작 (해당 시)
- [ ] 인증·가족 접근 변경은 호출자 권한, 가족 관계, REST/WebSocket 경로의 인가가 일치하는가
- [ ] 결제·포인트 변경은 멱등성, 동시성, 외부 PG와 DB의 트랜잭션 경계 및 실패 보상을 지키는가
- [ ] 이벤트·비동기·외부 호출 변경은 프록시 self-invocation, 발행 시점, 실패 후 상태와 임시 파일 정리를 확인했는가
- [ ] admin·스크립트·설정 변경은 해당 영역의 입력·출력·오류 전파와 호환성을 검증했는가

## 보고 형식

```
## Codex Review 결과

### ✅ 통과 항목
- (항목 목록)

### ❌ 문제 항목
- [파일경로:라인] 문제 설명
  → 수정 방향

### ⚠️ 경고 (PR 블로커 아님)
- (항목 목록)

### 판정
APPROVE / REQUEST_CHANGES
```

## 하지 말 것

- 리뷰만 요청받은 경우 코드를 직접 수정하기 (수정은 implement 또는 작성 에이전트가 담당한다).
- LLD가 없는 경우 무조건 통과시키기. LLD 작성 대상인지 먼저 판단하고, 예외라면 N/A 사유를 확인한 뒤 코딩 규칙과 모듈 배치를 검수한다.
