#!/usr/bin/env bash
# 배포 결과를 Discord로 알린다(LLD-0084). 알림 실패가 배포 결과를 바꾸지 않도록 항상 0으로 끝낸다.
#
# 입력(환경변수): RESULT(success|failure|cancelled|skipped), ENV_TAG(DEV|PROD), DEPLOY_LABEL(기본 "배포"),
#   FAILED_STEP, COMMIT_SHA, COMMIT_MESSAGE, ACTOR, RUN_URL, STARTED_AT(ISO8601, 없으면 GH_TOKEN으로 조회),
#   DISCORD_DEPLOY_WEBHOOK_URL. DRY_RUN=1이면 보내지 않고 JSON만 출력한다.
set -uo pipefail

result="${RESULT:-failure}"
# 새 push가 이전 배포를 취소한 경우는 새 배포가 결과를 알린다.
if [ "$result" = "cancelled" ] || [ "$result" = "skipped" ]; then
  echo "Deploy notification skipped: $result"
  exit 0
fi

label="${DEPLOY_LABEL:-배포}"
env_tag="${ENV_TAG:-DEV}"
if [ "$result" = "success" ]; then
  title="✅ [$env_tag] $label 완료"
  color=3066993
else
  title="❌ [$env_tag] $label 실패"
  color=15022157
  if [ -n "${FAILED_STEP:-}" ]; then
    title="$title · $FAILED_STEP"
  fi
fi

commit_message="${COMMIT_MESSAGE:-}"
if [ -z "$commit_message" ] && git rev-parse --git-dir >/dev/null 2>&1; then
  commit_message="$(git log -1 --format=%s 2>/dev/null || true)"
fi
commit_title="$(printf '%s' "$commit_message" | head -n 1)"

started_at="${STARTED_AT:-}"
if [ -z "$started_at" ] && [ -n "${GH_TOKEN:-}" ] && [ -n "${GITHUB_RUN_ID:-}" ]; then
  started_at="$(curl -fsS -m 10 -H "Authorization: Bearer $GH_TOKEN" \
    "${GITHUB_API_URL:-https://api.github.com}/repos/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID" \
    | jq -r '.run_started_at // empty' 2>/dev/null || true)"
fi

commit_sha="${COMMIT_SHA:-unknown}"
meta="${commit_sha:0:7} · ${ACTOR:-unknown}"
if [ -n "$started_at" ]; then
  minutes="$(jq -n --arg s "$started_at" '((now - ($s | fromdateiso8601)) / 60) | floor' 2>/dev/null || true)"
  if [ -n "$minutes" ]; then
    meta="$meta · ${minutes}분"
  fi
fi

payload="$(jq -n \
  --arg title "$title" --argjson color "$color" \
  --arg commit "$commit_title" --arg meta "$meta" --arg run "${RUN_URL:-}" \
  '{
     embeds: [{
       title: $title,
       color: $color,
       description: ([($commit | .[0:200]), $meta, (if $run == "" then empty else "🔗 [실행 기록 보기](\($run))" end)]
                     | map(select(. != "")) | join("\n"))
     }],
     allowed_mentions: {parse: []}
   }')"

if [ "${DRY_RUN:-}" = "1" ]; then
  printf '%s\n' "$payload"
  exit 0
fi
if [ -z "${DISCORD_DEPLOY_WEBHOOK_URL:-}" ]; then
  echo "DISCORD_DEPLOY_WEBHOOK_URL is not set; skipping deploy notification."
  exit 0
fi
if ! curl -fsS -m 10 -H "Content-Type: application/json" -d "$payload" "$DISCORD_DEPLOY_WEBHOOK_URL" >/dev/null; then
  echo "Deploy notification failed; deploy result is unchanged."
fi
exit 0
