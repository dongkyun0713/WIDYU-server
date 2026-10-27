#!/usr/bin/env bash
# 회원별 센서·위치 수신 상태를 본다(LLD-0108). 읽기 전용이며 좌표·심박 값은 출력하지 않는다.
# last_* 열은 서버가 저장한 시각이다. 늦게 도착한 자료도 도착한 때로 보인다.
#
# 개발 서버에서 실행한다. 로컬에서 바로 보려면:
#   ssh widyu-dev 'bash -s' -- 28 29 30 < scripts/ops/sensor-status.sh
# 환경변수: WINDOW_MIN(최근 몇 분을 셀지, 기본 10), MYSQL_CONTAINER(기본 widyu-mysql-dev)
set -euo pipefail

if [ "$#" -eq 0 ]; then
  echo "사용법: sensor-status.sh <회원ID> [회원ID...]" >&2
  exit 1
fi
for id in "$@"; do
  case "$id" in
    '' | *[!0-9]*) echo "회원 ID는 숫자여야 합니다: $id" >&2; exit 1 ;;
  esac
done
window_min="${WINDOW_MIN:-10}"
case "$window_min" in
  '' | *[!0-9]*) echo "WINDOW_MIN은 숫자여야 합니다" >&2; exit 1 ;;
esac
ids="$(IFS=,; echo "$*")"

docker exec -i "${MYSQL_CONTAINER:-widyu-mysql-dev}" sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --default-character-set=utf8mb4 --table "$MYSQL_DATABASE"' <<SQL
SET NAMES utf8mb4;
SET @since = (UNIX_TIMESTAMP() - ${window_min} * 60) * 1000;
SELECT NOW() AS now_kst, ${window_min} AS window_min;
SELECT m.id, m.name,
  (SELECT DATE_FORMAT(FROM_UNIXTIME(MAX(persisted_at_ms) / 1000), '%m-%d %H:%i:%s') FROM location_fix f WHERE f.member_id = m.id) AS last_location,
  (SELECT COUNT(*) FROM location_fix f WHERE f.member_id = m.id AND f.persisted_at_ms >= @since) AS loc_n,
  (SELECT DATE_FORMAT(FROM_UNIXTIME(MAX(persisted_at_ms) / 1000), '%m-%d %H:%i:%s') FROM sensor_batch s WHERE s.member_id = m.id AND s.stream = 'hr') AS last_hr,
  (SELECT COUNT(*) FROM sensor_batch s WHERE s.member_id = m.id AND s.stream = 'hr' AND s.persisted_at_ms >= @since) AS hr_n,
  (SELECT DATE_FORMAT(FROM_UNIXTIME(MAX(persisted_at_ms) / 1000), '%m-%d %H:%i:%s') FROM sensor_batch s WHERE s.member_id = m.id AND s.stream LIKE 'imu%') AS last_imu,
  (SELECT COALESCE(SUM(acc_n > 0), 0) FROM sensor_batch s WHERE s.member_id = m.id AND s.stream LIKE 'imu%' AND s.persisted_at_ms >= @since) AS acc_n,
  (SELECT COALESCE(SUM(gyro_n > 0), 0) FROM sensor_batch s WHERE s.member_id = m.id AND s.stream LIKE 'imu%' AND s.persisted_at_ms >= @since) AS gyro_n,
  (SELECT s.watch_battery_pct FROM sensor_batch s WHERE s.member_id = m.id AND s.watch_battery_pct IS NOT NULL ORDER BY s.persisted_at_ms DESC LIMIT 1) AS watch_batt,
  (SELECT DATE_FORMAT(FROM_UNIXTIME(MAX(persisted_at_ms) / 1000), '%m-%d %H:%i:%s') FROM device_heartbeat h WHERE h.member_id = m.id) AS last_heartbeat,
  (SELECT CONCAT('conn=', IFNULL(h.watch_connected, '-'), ' body=', IFNULL(h.watch_on_body, '-'), ' batt=', IFNULL(h.watch_battery_pct, '-'))
     FROM device_heartbeat h WHERE h.member_id = m.id ORDER BY h.ts_ms DESC LIMIT 1) AS heartbeat_watch
FROM member m WHERE m.id IN (${ids}) ORDER BY m.id;
SQL
