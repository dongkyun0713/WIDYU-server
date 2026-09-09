#!/usr/bin/env bash
# install-hooks.sh — 선택적 Claude 호환 설치. Codex는 .agents/skills를 직접 탐색한다.
# Codex 훅은 .codex/config.toml + /hooks 신뢰로 적용한다(docs/harness/codex.md).
#
# 사용: bash scripts/harness/install-hooks.sh
#
# 훅 설정(.claude/settings.json)은 이제 git 으로 추적되므로 복사 단계가 없다.
# clone·pull 하면 아래 훅이 그대로 적용된다:
#   PreToolUse(Edit|Write)  → pre-edit-branch-guard.sh
#   PostToolUse(Edit|Write) → on-file-edit.sh + audit-log.sh
#   PostToolUse(Bash)       → audit-log.sh
#   Stop                    → on-stop.sh + audit-log.sh

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SETTINGS="$ROOT/.claude/settings.json"

mkdir -p "$ROOT/.claude"

# .agents/skills 는 Codex 용 경로라 Claude Code 가 자동 탐색하지 않는다.
# SKILL.md 에 name/description 프론트매터가 이미 있으므로 심링크만 걸면
# 그대로 슬래시 커맨드로 잡힌다. (CLAUDE.md 문장에만 의존하면 로딩이 확률적이다)
# -e 로 판정하면 안 된다. 일반 파일·디렉터리에도 참이라 링크가 없는데 "존재"로 보고하고,
# 반대로 깨진 심링크에는 거짓이라 ln 이 "File exists" 로 죽는다 (set -e 로 스크립트 중단).
SKILL_LINK="$ROOT/.claude/skills"
SKILL_TARGET="../.agents/skills"

if [[ -L "$SKILL_LINK" ]]; then
  CURRENT_TARGET="$(readlink "$SKILL_LINK")"
  if [[ "$CURRENT_TARGET" == "$SKILL_TARGET" ]]; then
    echo "✅ 스킬 링크 확인: .claude/skills → $SKILL_TARGET"
  else
    echo "❌ .claude/skills 가 다른 대상을 가리킵니다: $CURRENT_TARGET" >&2
    echo "   기대값: $SKILL_TARGET — 확인 후 직접 지우고 다시 실행하세요." >&2
    exit 1
  fi
elif [[ -e "$SKILL_LINK" ]]; then
  echo "❌ .claude/skills 가 심링크가 아닌 일반 파일·디렉터리입니다." >&2
  echo "   내용을 확인한 뒤 옮기거나 지우고 다시 실행하세요." >&2
  exit 1
else
  ln -s "$SKILL_TARGET" "$SKILL_LINK"
  echo "✅ 스킬 연결: .claude/skills → $SKILL_TARGET"
fi

# 존재만 보면 추적되지 않은 옛 로컬 파일을 팀 정책 파일로 잘못 보고한다.
# 그 상태로 pull 하면 "untracked working tree file would be overwritten" 로 막힌다.
if git -C "$ROOT" ls-files --error-unmatch -- .claude/settings.json >/dev/null 2>&1; then
  echo "✅ 훅 설정 확인: $SETTINGS (git 추적 파일)"
  exit 0
fi

{
  echo "❌ .claude/settings.json 이 git 추적 상태가 아닙니다."
  echo ""
  if [[ -f "$SETTINGS" ]]; then
    echo "   예전 install-hooks.sh 가 복사해 둔 로컬 파일로 보입니다. 아래 순서로 전환하세요."
    echo "     1) 개인 허용 규칙이 있으면 .claude/settings.local.json 으로 옮깁니다"
    echo "     2) cp .claude/settings.json /tmp/settings.json.bak   # 백업"
    echo "     3) rm .claude/settings.json"
    echo "     4) git pull origin develop"
  else
    echo "   develop 을 최신화하면 팀 공유 설정이 내려옵니다."
    echo "     git pull origin develop"
  fi
  echo ""
  echo "   전환 후 이 스크립트를 다시 실행하세요."
} >&2
exit 1
