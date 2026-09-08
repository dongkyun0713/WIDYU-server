#!/usr/bin/env bash
# 기존 Claude/Codex 호출 경로 유지. domain 변경은 API 소비자도 검증한다.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
if [[ $# -gt 1 ]]; then
    echo "사용법: $0 [api|domain|all|모듈 내 파일 경로]" >&2
    exit 2
fi
case "${1:-}" in
    "") exec python3 "$ROOT_DIR/scripts/harness/verify.py" --tests-only ;;
    domain|*widyu-domain/*) module=domain ;;
    api|*widyu-api/*) module=api ;;
    all) module=all ;;
    *) echo "지원하지 않는 모듈/경로: $1" >&2; exit 2 ;;
esac
exec python3 "$ROOT_DIR/scripts/harness/verify.py" --tests-only --module "$module"
