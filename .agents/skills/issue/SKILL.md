---
name: issue
description: WIDYU-server에서 GitHub Issue를 생성하고 필요하면 feature/{issue-number} 브랜치를 만든다.
---

# issue

WIDYU-server 작업을 시작하기 전에 GitHub Issue를 생성하고, 필요하면 `feature/{issue-number}` 브랜치를 만든다.

## 핵심 원칙

- 현재 브랜치·변경·worktree를 먼저 확인한다. 새 작업은 최신 develop 기준으로 만들고, 기존 작업을 이어가는 경우 해당 이슈·브랜치를 재사용한다.
- 원본 이슈 저장소는 `GB-able/WIDYU-server`, 작업 fork는 `dongkyun0713/WIDYU-server`다. gh 명령에는 `--repo GB-able/WIDYU-server`를 명시한다.
- 미커밋 변경이 있거나 다른 작업의 브랜치라면 해당 작업을 stash/reset/switch로 옮기지 않고 새 worktree를 만든다.
- 이슈 생성 전 LLD가 있으면 LLD 링크를 본문에 포함한다.
- 이슈 제목과 본문은 한글 존댓말로 작성한다.
- 작업 범위가 크면 분리안을 제안한다.
- 미결정 사항은 `확인 필요`로 남긴다.
- 브랜치는 사용자 요청 시 또는 바로 작업할 때만 만든다.
- 기본 Assignee: `dongkyun0713`.
- 변경 성격에 맞는 label을 확인하고 가능하면 함께 지정한다.

## 절차

1. `git status --short --branch`, `git worktree list`, remote를 확인한다. 새 작업이면 아래 순서로 기준 ref를 최신화한다. 충돌/분기 발생 시 강제 동기화하지 않는다.
   - `gh repo sync dongkyun0713/WIDYU-server --source GB-able/WIDYU-server --branch develop`
   - `git fetch origin develop`
   - 깨끗한 develop에서 작업할 때만 `git pull --ff-only origin develop`. 새 worktree는 `origin/develop`에서 직접 만들 수 있다.
2. `gh issue list --repo GB-able/WIDYU-server --state open --limit 30 --json number,title,labels`로 중복 확인.
3. 관련 LLD가 `docs/lld/`에 있으면 이슈 본문에 링크를 건다.
4. 아래 템플릿으로 본문 작성.
5. `gh label list --repo GB-able/WIDYU-server --limit 50`로 사용 가능한 label을 확인한다.
6. `gh issue create --repo GB-able/WIDYU-server --title "<title>" --body-file <tmpfile> --assignee dongkyun0713 --label "<label>"`.
7. `gh issue view <N> --repo GB-able/WIDYU-server --json assignees,labels`로 assignee와 label 반영을 확인한다.
8. 바로 작업하면 `git worktree add -b feature/<issue-number> <새-작업경로> origin/develop`로 별도 작업 폴더를 만든다. 현재 폴더가 깨끗하고 전환해도 되는 경우 `git switch -c feature/<issue-number> origin/develop`도 가능하다. 생성 후 `git branch --unset-upstream feature/<issue-number>`로 새 브랜치의 upstream 추적을 해제해 실수로 develop에 push하지 않게 한다.
9. 이슈 번호, URL, 브랜치명 보고.

### Label 선택 기준

| 변경 성격 | label |
| --- | --- |
| 문서/가이드/스킬 문서 | `Docs` |
| 개발 환경, CI, 설정, 하네스 | `Setting` |
| 테스트 코드/검증 보강 | `Test` |
| 기능 구현 | `Feature` |
| 버그 수정 | `bug` |
| 구조 개선 | `Refactor` |
| 배포/인프라 | `Devops` |

## 이슈 본문 템플릿

본문은 존댓말 종결형(`합니다`, `습니다`, `필요합니다`)으로 작성한다.

```markdown
## 배경
<왜 필요한 작업인지 존댓말로 작성합니다.>

## 관련 설계
- LLD: docs/lld/<있으면 경로>
- ADR: docs/adr/<있으면 경로>

## 작업 범위
- 변경 모듈: widyu-api / widyu-domain
- <구현/문서/테스트 단위를 존댓말로 작성합니다.>

## 완료 조건
- <LLD 인수조건 기반>

## 정책 확인 필요
- <있으면 작성, 없으면 "없습니다.">
```

## 하지 말 것

- 중복 이슈 확인 없이 생성.
- 이슈 생성만 요청했는데 커밋/PR까지 진행.
- develop에서 바로 작업.
