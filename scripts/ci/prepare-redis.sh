#!/usr/bin/env bash
set -euo pipefail

: "${RUNNER_TEMP:?RUNNER_TEMP is required}"
: "${GITHUB_PATH:?GITHUB_PATH is required}"

# redis-tools installs runtime dependencies without starting a host Redis service.
sudo apt-get update
sudo apt-get install -y --no-install-recommends redis-tools
redis_test_root=$(mktemp -d "$RUNNER_TEMP/auth-redis.XXXXXX")
mkdir -p "$redis_test_root/packages" "$redis_test_root/bin"
cd "$redis_test_root/packages"
apt-get download redis-server redis-tools
for package in ./*.deb; do
  dpkg-deb --extract "$package" "$redis_test_root/bin"
done
"$redis_test_root/bin/usr/bin/redis-server" --version
echo "$redis_test_root/bin/usr/bin" >> "$GITHUB_PATH"
