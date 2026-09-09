---
name: commit
description: WIDYU-server에서 변경사항을 Conventional Commits 규칙으로 커밋한다.
---

# commit

WIDYU-server의 변경사항을 Conventional Commits 규칙에 따라 커밋한다.
타입·scope만 영문, 제목·본문은 한글.

## 핵심 원칙

- 사용자 요청에 커밋이 포함되면 수행한다. 이미 받은 커밋 권한을 다시 묻지 않는다.
- 하네스/스킬 변경 요청에는 `.agents/`, `.codex/`, AGENTS.md도 작업 범위에 포함한다. 요청과 무관한 개인 설정·런타임 기록은 스테이징하지 않는다.
- `docs/adr`, `docs/lld`, `docs/erd`는 관련 구현 변경의 설계 산출물이면 함께 스테이징한다.
- 엔티티 변경이 포함되면 `./gradlew compileJava` 실행 여부를 먼저 확인한다.

## 절차

1. `git status --short --branch`로 브랜치와 변경 파일 확인.
2. `git diff`, `git diff --cached`, untracked 파일을 확인하고 검증 결과를 확인한다. 기존 staged 변경도 사용자 작업이면 보존한다.
3. 논리적 단위로 분리. 관련 없는 변경 제외.
4. 엔티티 변경 포함 시 Q-클래스 재생성 여부 확인.
5. 의도한 파일만 스테이징.
6. 관련 이슈가 있으면 `Refs #N` 또는 `Closes #N` 추가.

## 커밋 메시지 형식

```
<type>(<scope>): <한글 제목, 마침표 없음>

<한글 본문 — 왜 변경했는지>

Refs #<issue-number>
Co-Authored-By: Codex <codex@openai.com>
```

### type

`feat` / `fix` / `docs` / `refactor` / `perf` / `test` / `build` / `ci` / `chore`

### scope (WIDYU 도메인 기준)

| scope | 대상 |
|-------|------|
| `auth` | 인증/인가 |
| `album` | 앨범/사진/영상 |
| `health` | 건강 목표 |
| `pay` | 포인트/결제 |
| `location` | 실시간 위치 |
| `member` | 회원 정보 |
| `family` | 가족 그룹 |
| `notification` | 알림/FCM |
| `domain` | widyu-domain 모듈 |
| `ci` | GitHub Actions |
| `harness` | 지침·스킬·에이전트 검증 설정 |

## 예시

```
feat(album): 앨범 공유 FCM 알림 전송 추가

LLD-0012 기준으로 @EventListener 방식으로 구현.
도메인 간 결합 제거.

Refs #42
Co-Authored-By: Codex <codex@openai.com>
```

## 하지 말 것

- 엔티티 변경 후 compileJava 생략.
- 미완성 테스트를 통과했다고 쓰기.
- 관련 없는 변경 묶기.
