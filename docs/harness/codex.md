# WIDYU Codex 하네스 운영

Codex가 기본 구현 에이전트다. 기존 ADR → LLD → 테스트 시나리오 → 구현 → 검수 흐름을 유지한다. 결정은 [ADR-0022](../adr/ADR-0022-codex-primary-harness.md), 구현·검증 기준은 [LLD-0026](../lld/LLD-0026-codex-primary-harness.md)에서 관리한다.

## 파일 역할

| 위치 | 역할 |
| --- | --- |
| [루트 AGENTS.md](../../AGENTS.md) | 공통 작업 순서·구조·완료 기준 |
| [backend/AGENTS.md](../../backend/AGENTS.md) | 기존 도메인 지도·불변식·테스트 규칙 |
| [admin/AGENTS.md](../../admin/AGENTS.md) | React 대시보드 실행·검증 |
| [.agents/skills](../../.agents/skills) | issue / implement / review / commit / pr의 원본 |
| [.codex/config.toml](../../.codex/config.toml) | Codex 훅과 Figma MCP 연결 |
| [scripts/harness](../../scripts/harness) | 공통 검사와 Codex/Claude 어댑터 |
| CLAUDE.md, backend/CLAUDE.md, admin/CLAUDE.md | 같은 디렉터리 AGENTS.md를 import하는 Claude 호환 진입점 |
| .claude/settings.json | 기존 Claude 전용 권한·훅 설정 |

AGENTS.md는 루트에서 현재 작업 디렉터리까지 계층적으로 발견된다. 루트 세션이 backend/admin으로 작업 범위를 넓히면 해당 AGENTS.md를 직접 읽도록 루트에도 명시했다. 기본 문서 한도 32 KiB 안에서 유지하며 CLAUDE.md fallback이나 전역 설정 변경은 필요 없다. [공식 지침 탐색 문서](https://developers.openai.com/codex/guides/agents-md/)

## 시작하기

1. 작업할 checkout/worktree에서 Codex를 연다. 새 설정·지침을 반영하려면 새 세션을 시작한다.
2. 프로젝트를 신뢰한 환경에서 `.codex/config.toml`이 로드된다. CLI의 `/hooks`에서 두 훅 정의를 읽고 신뢰한다. 변경된 훅은 다시 검토해야 한다. 자동 신뢰 우회 옵션은 사용하지 않는다.
3. CLI `/skills`에서 저장소 스킬을 확인한다. `$implement`, `$review`, `$issue`, `$commit`, `$pr`로 명시하거나 자연어로 요청한다. `.agents/skills`는 자동 탐색되므로 Codex용 설치/심링크는 필요 없다. [공식 스킬 문서](https://developers.openai.com/codex/skills/)
4. 백엔드는 Java 21, Python 3, Git, Bash와 테스트에 필요한 Redis를 준비한다. admin은 lockfile에 맞춰 `npm ci` 후 검증한다. Docker 실행은 기존 `scripts/docker/{dev,prod}-up.sh`, 종료는 `{dev,prod}-down.sh`, 로그는 `scripts/docker/logs.sh {dev|prod} [service]`를 사용하며 배포 요청 범위에서 실행한다.
5. Figma 작업 시 `FIGMA_MCP_AUTHORIZATION`, `FIGMA_PAT`를 Codex 프로세스에 환경변수로 전달한다. 기존 프로젝트 로컬 Figma 주소와 변수 이름을 보존했다. 자격증명은 저장소에 넣지 않는다.

설치된 CLI `0.153.4`에서 `codex features list`의 hooks 지원을 확인했다. 모델·추론 강도·sandbox·승인 정책은 개인/조직 설정을 따른다. 프로젝트 설정으로 전역 권한이나 모델을 강제하지 않는다. 기존 Claude 권한 문자열을 Codex 설정 키로 복사하지 않는다. [공식 설정 참조](https://developers.openai.com/codex/config-reference/)

## 자동 검사와 완료 검사

| 경로 | 동작 |
| --- | --- |
| Codex PreToolUse / Bash | 기존 pre-bash-guard.sh로 위험 명령·시크릿 읽기 패턴 검사 |
| Codex PreToolUse / apply_patch | main/master/develop 및 detached HEAD 차단. 작업 브랜치도 세션 최초 편집을 차단하고 세션×브랜치 ack 요구 |
| Codex Stop | 작업 트리 Java 정적 검사. 실패하면 후속 수정 요청 1회, 재진입에서는 미해결 경고. LLM 호출·컴파일·테스트 없음 |
| verify.sh | 정적 검사 → backend compileJava·영향 모듈 테스트 → admin lint/build |
| review 스킬 | LLD 인수조건·권한·트랜잭션·실패 동작 검수 |
| CI | 하네스 회귀 검사·base 대비 Java 규칙, Domain/API 테스트, admin 빌드 |

Codex의 Bash/exec_command는 훅에서 `Bash`, apply_patch는 `apply_patch`로 매칭되며 둘 다 `tool_input.command`를 사용한다. Claude Edit의 `file_path` 어댑터를 그대로 연결하지 않았다. 훅은 현재 Git root를 사용하므로 하위 디렉터리·worktree에서도 동작한다. 프로젝트 신뢰와 훅 신뢰가 필요하며 클라이언트 정책에 따라 실행되지 않을 수 있다. [공식 훅 문서](https://developers.openai.com/codex/hooks/)

작업 브랜치의 첫 apply_patch에서는 훅이 한 번 멈춘다. 새 작업이면 issue 스킬로 새 이슈와 브랜치/worktree를 만든다. 현재 사용자 요청이 그 브랜치의 기존 이슈를 이어가는 것이 확인되면 훅이 안내한 명령으로 `.codex/state/branch-ack-*.txt`를 만든다. ack는 전체 session ID와 전체 브랜치 이름의 해시를 포함하므로 다른 세션이나 `feature/foo`/`feature-foo`처럼 이름이 비슷한 브랜치와 공유되지 않는다. 보호 브랜치는 ack로도 통과할 수 없다. 같은 세션·브랜치에서 나중에 별개의 새 요청이 시작되는 경우 훅은 의미 변화를 자동 판별하지 못하므로 issue 스킬의 새 작업 확인 절차가 계속 필요하다.

```bash
# staged + unstaged + untracked 기준 완료 검사
bash scripts/harness/verify.sh

# PR 전체: 커밋된 변경까지 포함
git fetch origin develop
bash scripts/harness/verify.sh --base "$(git merge-base origin/develop HEAD)"

# 빠른 정적 검사 (전체 검증을 대체하지 않음)
bash scripts/harness/verify.sh --static-only

# domain은 API 소비자도 테스트
bash scripts/harness/run-module-tests.sh domain

# 하네스 변경 검증
python3 -m unittest discover -s scripts/harness -p 'test_*.py'
python3 scripts/harness/test-pre-bash-guard.py
python3 scripts/harness/test-pre-edit-branch-guard.py
```

기본 base는 HEAD다. 명시한 base는 현재 working tree와 비교하며 신규 파일도 합친다. rename은 이전/이후 경로, 삭제는 영향 모듈 판단에 포함한다. 정적 검사기는 같은 기준 ref를 `HARNESS_DIFF_BASE`로 전달받는다. 테스트/리소스 변경도 모듈을 선택하며 domain 또는 공통 Gradle 변경은 domain과 API를 함께 실행한다. 문서만 바뀌면 애플리케이션 검증을 생략했다고 출력한다. 검증 스크립트는 의존성 설치·서비스 기동을 대신하지 않는다.

정적 검사기는 기존 grep/awk 규칙을 재사용하므로 모든 Java 문법·애노테이션 순서를 판별하지 못한다. Stop 성공은 컴파일/테스트/의미 검수 성공이 아니다. 타임아웃·실패·미실행을 완료로 보고하지 않는다. 훅 가드는 모든 셸·MCP 쓰기를 차단하는 보안 경계가 아니다. 파일 보호는 실행 환경의 권한 정책과 자격증명 관리가 담당한다.

## 기존 .claude 및 스킬 검토 결과

| 기존 항목 | 처리 |
| --- | --- |
| settings.json의 deny/ask와 PreToolUse/PostToolUse/Stop | Claude 전용 설정 유지. 공통 명령 가드·Java 검사 재사용 |
| settings.local.json의 개인 allow, MCP 활성 목록 | 개인 설정 유지, 공유 config에 일괄 복사하지 않음 |
| skills 심링크 | 공통 원본 .agents/skills 유지. Claude가 필요하면 install-hooks.sh 실행 |
| RESUME.md·checkpoint ref | 과거 Claude 세션용. 새 Codex 작업의 근거로 자동 주입하지 않음 |
| audit/·state/·stdin-samples/·suggestions/ | Claude 로그·테스트 입력·검수 산출물로 유지. 개인 데이터 원문을 공유 문서로 옮기지 않음 |
| .codex-review-off | 기존 Claude Stop의 중첩 Codex 리뷰 비활성 표시. 새 Codex 검사와 무관 |
| issue | 기존 작업 확인 후 최신 develop 기준 별도 worktree 지원, 원본 GitHub 저장소 명시 |
| implement | Claude 대체 수단에서 기본 구현 절차로 전환, LLD 작성 기준·예외와 정합성 확보 |
| review | 작성 에이전트와 무관한 검수, staged/untracked/커밋 범위 및 조건부 도메인 기준 |
| commit | 요청 범위에서 실행, 하네스 변경 파일 포함, 기존 staged 사용자 변경 보존 |
| pr | LLD 또는 N/A 사유, 실제 diff·검증 증거, fork head/base 및 기존 PR 갱신 |

Claude를 다시 사용할 때는 `bash scripts/harness/install-hooks.sh`로 스킬 링크를 준비한다. Claude Stop은 기존 정적 검사·컴파일·선택적 Codex 리뷰를 수행하며 전체 모듈 테스트 완료를 보장하지 않는다. Codex 기본 경로에서는 이 Stop 스크립트나 codex-review.sh를 자동 호출하지 않는다. 개인 Codex 재개 기록은 gitignore된 `.codex/RESUME.md`를 사용할 수 있다.

## 참고한 문서와 공개 사례

2026-09-07 확인. 아래 사례에서 구조·판단 기준을 참고하고 WIDYU의 도메인 규칙과 기존 ADR/LLD 흐름에 맞춰 작성했다. 외부 저장소의 지침 자체를 실행 규칙으로 가져오지 않았다.

- [AGENTS.md 공식 사이트](https://agents.md/): 사람용 README와 에이전트용 지침을 구분하고, 실행 명령·검증·하위 영역 지침을 제공하는 형식.
- [OpenAI Codex의 AGENTS.md](https://github.com/openai/codex/blob/main/AGENTS.md): 프로젝트 고유 규칙과 구체적인 검사 명령을 함께 유지하는 사례. Rust 규칙은 WIDYU에 이식하지 않았다.
- [Peter Steinberger의 agent-scripts](https://github.com/steipete/agent-scripts/blob/main/AGENTS.MD): 공통 지침의 단일 원본, 작업 권한 범위와 완료 증거를 명확히 하는 운영 사례. 개인 경로·말투·배포 정책은 가져오지 않았다.
- 공식 Codex 지침·스킬·설정·훅 문서는 위 적용 항목에 각각 연결했다. 문서와 설치 버전의 지원 범위가 달라지면 먼저 확인하고, 자동 훅이 없어도 공통 검증 명령을 직접 실행한다.
