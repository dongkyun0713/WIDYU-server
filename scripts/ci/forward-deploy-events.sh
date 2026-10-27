#!/usr/bin/env bash
# SSH stdout의 배포 단계 신호만 Actions runner에서 Discord로 전달한다.
# webhook은 서버나 SSH 인수로 넘기지 않는다. 상위 pipeline은 pipefail을 켜 SSH 실패를 보존해야 한다.
set -uo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in
    WIDYU_DEPLOY_EVENT=app_stopped|WIDYU_DEPLOY_EVENT=app_restarted|WIDYU_DEPLOY_EVENT=app_rollback)
      printf '%s\n' "$line"
      RESULT="${line#WIDYU_DEPLOY_EVENT=}" bash "$script_dir/notify-discord-deploy.sh" || true
      ;;
    *) printf '%s\n' "$line" ;;
  esac
done
