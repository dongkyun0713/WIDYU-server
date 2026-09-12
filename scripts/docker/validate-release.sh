#!/usr/bin/env bash
# Scan tracked release sources, including on a clean checkout.
set -euo pipefail
cd "$(dirname "$0")/../.."
files=$(mktemp)
trap 'rm -f "$files"' EXIT
git ls-files -z -- 'backend/widyu-api/src/main/java/*.java' 'backend/widyu-domain/src/main/java/*.java' > "$files"
test -s "$files" || { echo "No release Java sources found." >&2; exit 1; }
failed=0
while IFS= read -r -d '' file; do
  if [[ ! -f "$file" ]]; then
    echo "Missing release source: $file" >&2
    failed=1
  elif ! HARNESS_FULL_FILE=1 HARNESS_DIFF_BASE=HEAD bash scripts/harness/validate-java-rules.sh "$file"; then
    failed=1
  fi
done < "$files"
exit "$failed"
