#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
USER_DATA="${USER_DATA:-$PROJECT_ROOT/k6/heart-rate-users.local.csv}"
RESULTS_ROOT="${RESULTS_ROOT:-$PROJECT_ROOT/benchmarks/heart-rate}"
RUN_LABEL="${RUN_LABEL:-stub}"
TARGETS="${TARGETS:-websocket ai}"
PATHS="${PATHS:-single batch}"
ROUTES="${ROUTES:-normal emergency}"
VUS_LEVELS="${VUS_LEVELS:-1 10 25 50 100}"
REPEATS="${REPEATS:-3}"
WARMUP_SECONDS="${WARMUP_SECONDS:-60}"
MEASURE_SECONDS="${MEASURE_SECONDS:-300}"
DRAIN_SECONDS="${DRAIN_SECONDS:-15}"
METRICS_SAMPLE_SECONDS="${METRICS_SAMPLE_SECONDS:-5}"
WS_URL="${WS_URL:-ws://localhost:8080/ws/location}"
AI_URL="${AI_URL:-http://localhost:5000}"
ACTUATOR_URL="${ACTUATOR_URL:-http://localhost:8080/actuator/prometheus}"
STUB_METRICS_URL="${STUB_METRICS_URL:-}"
RESOURCE_CONTAINERS="${RESOURCE_CONTAINERS:-widyu-api-dev widyu-ai widyu-mysql-dev widyu-redis-dev}"
CASE_RESET_SCRIPT="${CASE_RESET_SCRIPT:-}"
HEALTH_URL="${HEALTH_URL:-http://localhost:8080/actuator/health}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-180}"
collector_pid=""

cleanup() {
  if [ -n "$collector_pid" ]; then
    kill "$collector_pid" 2>/dev/null || true
    wait "$collector_pid" 2>/dev/null || true
  fi
}

trap cleanup EXIT

if [ ! -f "$USER_DATA" ]; then
  echo "사용자 CSV가 없습니다: $USER_DATA" >&2
  echo "k6/heart-rate-users.example.csv를 복사하고 테스트 전용 member_id와 JWT를 입력하세요." >&2
  exit 1
fi

for command_name in k6 curl jq; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "필수 명령이 없습니다: $command_name" >&2
    exit 1
  fi
done

max_vus=0
for level in $VUS_LEVELS; do
  if [ "$level" -gt "$max_vus" ]; then
    max_vus="$level"
  fi
done

available_users="$(awk 'NR > 1 && NF > 0 { count += 1 } END { print count + 0 }' "$USER_DATA")"
if [ "$available_users" -lt "$max_vus" ]; then
  echo "사용자 CSV는 ${available_users}명이고 최대 VUS는 ${max_vus}입니다." >&2
  exit 1
fi

timestamp="$(date -u '+%Y%m%dT%H%M%SZ')"
run_root="$RESULTS_ROOT/${timestamp}-${RUN_LABEL}"
mkdir -p "$run_root"

docker_version="unavailable"
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  docker_version="$(docker version --format '{{.Server.Version}}')"
fi

jq -n \
  --arg recorded_at "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
  --arg git_sha "$(git -C "$PROJECT_ROOT" rev-parse HEAD)" \
  --arg run_label "$RUN_LABEL" \
  --arg os "$(uname -a)" \
  --arg cpu_count "$(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo unknown)" \
  --arg memory_bytes "$(sysctl -n hw.memsize 2>/dev/null || awk '/MemTotal/ { print $2 * 1024 }' /proc/meminfo 2>/dev/null || echo unknown)" \
  --arg k6_version "$(k6 version | head -1)" \
  --arg docker_version "$docker_version" \
  --arg api_image "${API_IMAGE:-unknown}" \
  --arg ai_image "${AI_IMAGE:-unknown}" \
  --arg db_version "${DB_VERSION:-unknown}" \
  --arg redis_version "${REDIS_VERSION:-unknown}" \
  --arg jvm_options "${JVM_OPTIONS:-unknown}" \
  --arg resource_limits "${RESOURCE_LIMITS:-unknown}" \
  --arg data_scale "${DATA_SCALE:-unknown}" \
  --arg test_host "${TEST_HOST:-local}" \
  '{recordedAt: $recorded_at, gitSha: $git_sha, runLabel: $run_label, os: $os,
    cpuCount: $cpu_count, memoryBytes: $memory_bytes, k6Version: $k6_version,
    dockerVersion: $docker_version, apiImage: $api_image, aiImage: $ai_image,
    dbVersion: $db_version, redisVersion: $redis_version, jvmOptions: $jvm_options,
    resourceLimits: $resource_limits, dataScale: $data_scale, testHost: $test_host}' \
  > "$run_root/environment.json"

collect_metrics() {
  local output_dir="$1"
  local stop_file="$2"
  local stub_metrics
  while [ ! -f "$stop_file" ]; do
    {
      echo "# sampled_at $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
      curl --silent --show-error --max-time 3 "$ACTUATOR_URL" || true
    } >> "$output_dir/actuator.prom"

    if [ -n "$STUB_METRICS_URL" ]; then
      stub_metrics="$(curl --silent --max-time 3 "$STUB_METRICS_URL" || true)"
      if [ -n "$stub_metrics" ]; then
        jq -c --arg sampled_at "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
          '. + {sampledAt: $sampled_at}' <<< "$stub_metrics" >> "$output_dir/stub-metrics.jsonl"
      fi
    fi

    if [ "$docker_version" != "unavailable" ]; then
      {
        echo -e "sampled_at\tname\tcpu\tmemory_usage\tmemory_percent"
        for container_name in $RESOURCE_CONTAINERS; do
          docker stats --no-stream --format \
            "$(date -u '+%Y-%m-%dT%H:%M:%SZ')\t{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}" \
            "$container_name" 2>/dev/null || true
        done
      } >> "$output_dir/docker-stats.tsv"
    fi
    sleep "$METRICS_SAMPLE_SECONDS"
  done
}

run_case() {
  local target="$1"
  local transport_path="$2"
  local route="$3"
  local vus="$4"
  local repeat="$5"
  local output_dir="$run_root/$target/$transport_path/$route/vus-$vus/run-$repeat"
  local stop_file="$output_dir/.metrics-stop"
  mkdir -p "$output_dir"

  if [ -n "$CASE_RESET_SCRIPT" ]; then
    if [ ! -x "$CASE_RESET_SCRIPT" ]; then
      echo "CASE_RESET_SCRIPT를 실행할 수 없습니다: $CASE_RESET_SCRIPT" >&2
      exit 1
    fi
    "$CASE_RESET_SCRIPT" "$target" "$transport_path" "$route" "$vus" "$repeat"
    waited=0
    until curl --silent --fail --max-time 3 "$HEALTH_URL" >/dev/null; do
      if [ "$waited" -ge "$HEALTH_TIMEOUT_SECONDS" ]; then
        echo "API health check 시간 초과: $HEALTH_URL" >&2
        exit 1
      fi
      sleep 2
      waited=$((waited + 2))
    done
  fi

  collect_metrics "$output_dir" "$stop_file" &
  collector_pid=$!

  script="$PROJECT_ROOT/k6/heart-rate-stomp.js"
  if [ "$target" = "ai" ]; then
    script="$PROJECT_ROOT/k6/heart-rate-ai.js"
  fi

  set +e
  k6 run \
    -e "USERS_FILE=$USER_DATA" \
    -e "WS_URL=$WS_URL" \
    -e "AI_URL=$AI_URL" \
    -e "HEART_PATH=$transport_path" \
    -e "ROUTE=$route" \
    -e "VUS=$vus" \
    -e "WARMUP_SECONDS=$WARMUP_SECONDS" \
    -e "MEASURE_SECONDS=$MEASURE_SECONDS" \
    -e "DRAIN_SECONDS=$DRAIN_SECONDS" \
    -e "SUMMARY_PATH=$output_dir/summary.json" \
    "$script" > "$output_dir/k6.log" 2>&1
  exit_code=$?
  set -e

  touch "$stop_file"
  wait "$collector_pid" || true
  collector_pid=""
  rm "$stop_file"
  echo "$exit_code" > "$output_dir/exit-code.txt"
}

for target in $TARGETS; do
  if [ "$target" != "websocket" ] && [ "$target" != "ai" ]; then
    echo "TARGETS에는 websocket 또는 ai만 사용할 수 있습니다: $target" >&2
    exit 1
  fi
  for transport_path in $PATHS; do
    for route in $ROUTES; do
      for vus in $VUS_LEVELS; do
        repeat=1
        while [ "$repeat" -le "$REPEATS" ]; do
          echo "실행: label=$RUN_LABEL target=$target path=$transport_path route=$route vus=$vus repeat=$repeat"
          run_case "$target" "$transport_path" "$route" "$vus" "$repeat"
          repeat=$((repeat + 1))
        done
      done
    done
  done
done

python3 "$SCRIPT_DIR/summarize_heart_rate_results.py" "$run_root"
echo "결과: $run_root"
