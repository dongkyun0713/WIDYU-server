---
name: implement
description: Codex가 WIDYU-server의 기능·버그·설정 변경을 LLD와 인수조건에 따라 직접 구현하고 검증한다.
---

# implement

Codex가 기본 구현 에이전트로서 LLD를 기반으로 코드를 구현한다.
구현 완료 후 반드시 **review 스킬을 이어서 실행**해 자체 검수한다.

## 핵심 원칙

- LLD가 필요한 작업인데 문서가 없으면 구현 전 직접 작성한다. `docs/lld/README.md`의 예외에 해당하면 PR에 N/A 사유와 검증 가능한 완료 조건을 남긴다.
- LLD의 `## 9. 미결정 사항`이 비어 있지 않으면 해당 항목을 사용자에게 확인하고 진행한다.
- `CLAUDE.md`, `backend/CLAUDE.md`, `AGENTS.md`의 규칙을 함께 따른다.
- 구현 단위는 LLD 하나 = PR 하나를 원칙으로 한다.
- 현재 이슈·브랜치·diff를 먼저 확인하고 다른 작업의 변경을 섞지 않는다.

## 절차

1. 관련 LLD를 `docs/lld/`에서 읽는다. LLD가 필요한데 없으면 템플릿으로 작성한다.
2. 관련 ERD를 `docs/erd/`에서 읽는다. 데이터 모델 변경이 없어도 관련 도메인 구조를 확인하고, 관련 ERD가 없으면 없다고 기록한다.
3. LLD의 `## 9. 미결정 사항`을 확인하고 미결정 항목이 있으면 사용자에게 알린다.
4. LLD `## 2. 범위`에서 변경 모듈(widyu-api / widyu-domain)을 확인한다.
5. LLD `## 3. 인터페이스 / API`와 `## 4. 데이터 모델`을 기준으로 구현 계획을 세운다.
6. 계획과 완료 조건을 사용자에게 먼저 보고한다. 사용자가 이미 구현을 요청했다면 별도 승인을 기다리지 않고 진행한다.
7. 코드를 작성한다. 작성 중 `validate-java-rules.sh`를 참조해 규칙을 준수한다.
8. LLD `## 7. 인수조건`을 기반으로 테스트를 작성한다.
9. `bash scripts/harness/verify.sh`로 변경 범위를 검증한 뒤 **review 스킬을 실행**한다. 커밋된 변경까지 포함할 때는 `--base <merge-base>`를 전달한다.
10. review 결과 `REQUEST_CHANGES`이면 수정 후 다시 review를 실행한다.
11. review 결과 `APPROVE`이고 사용자 요청에 커밋이 포함되면 commit 스킬로 커밋한다. PR 요청도 포함되면 pr 스킬을 이어서 실행한다.

## 구현 규칙 (CLAUDE.md / backend/CLAUDE.md와 동일)

- 삼항 연산자 금지 — if/else 또는 early return
- Service/Facade에서 `new XxxResponse(` 금지 — `from()`/`of()` 팩토리 사용
- Controller에서 Repository 직접 import 금지
- 엔티티 → `widyu-domain`, 리포지토리/서비스/컨트롤러 → `widyu-api`
- `@Async` 메서드에 `@Transactional` 필수
- 여러 서비스 조합 시 Facade 패턴 사용
- 도메인 간 결합은 `@EventListener` 방식으로 분리

## 테스트 작성 규칙

- JUnit 5 + `@ExtendWith(MockitoExtension.class)`
- BDDMockito (`given/willReturn`) 사용
- 메서드명: 한글 언더스코어 (예: `앨범_업로드_시_FCM_전송`)
- `@DisplayName`: `<행위>하면/할 때 <결과>한다` 형식
- 상태 검증(`assertEquals`, `assertThat`) 우선
- 외부 호출, 이벤트 발행, 삭제 같은 side effect는 `verify` 허용

## 엔티티 변경 시 추가 작업

- `docs/erd/`의 ERD 문서를 수정한다.
- MySQL ENUM 변경이 있으면 `docs/lld/` 해당 LLD의 `## 8. 영향 범위`에 ALTER TABLE 명령을 기록한다.

## 하지 말 것

- LLD가 필요한 작업을 LLD 또는 명시적 N/A 사유 없이 구현.
- 미결정 사항을 임의로 결정하고 구현.
- review 스킬 없이 commit.
- LLD 범위 밖의 추가 기능 구현.
