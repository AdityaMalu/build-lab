#!/usr/bin/env bash
# Submits code that tries forbidden things in each language to a hosted-mode server and fails if
# anything gets through. Usage: .github/scripts/sandbox-probes.sh [base-url]
set -euo pipefail
BASE="${1:-http://localhost:8080}"
P=.github/sandbox-probes
S=projects/rate-limiter

check() {
  local lang="$1" body="$2" out
  out=$(curl -sf -X POST "$BASE/api/run/rate-limiter?lang=$lang" -H 'Content-Type: application/json' \
        --data-binary @"$body" | jq -r '.output')
  echo "--- $lang"
  echo "$out" | grep PROBE || true
  if echo "$out" | grep -q "PROBE ALLOWED\|PROBE BROKEN"; then
    echo "::error::sandbox gap in $lang"; exit 1
  fi
  local blocked
  blocked=$(echo "$out" | grep -c "PROBE BLOCKED" || true)
  if [ "$blocked" -lt 5 ]; then echo "::error::$lang probes did not all run ($blocked)"; echo "$out"; exit 1; fi
  echo "$out" | grep -q "PROBE OK" || { echo "::error::$lang could not use its own temp dir"; exit 1; }
}

tmp=$(mktemp -d)

jq -n --rawfile init "$S/python/starter/src/ratelimiter/__init__.py" \
      --rawfile lim "$S/python/starter/src/ratelimiter/limiters.py" \
      --rawfile probe "$P/probe.py" \
  '{"ratelimiter/__init__.py": ("from . import probe\n" + $init), "ratelimiter/limiters.py": $lim, "ratelimiter/probe.py": $probe}' \
  > "$tmp/python.json"
check python "$tmp/python.json"

jq -n --rawfile lim "$S/go/starter/src/ratelimiter/limiter.go" --rawfile probe "$P/probe.go" \
  '{"ratelimiter/limiter.go": $lim, "ratelimiter/probe.go": $probe}' > "$tmp/go.json"
check go "$tmp/go.json"

jq -n --rawfile hdr "$S/cpp/starter/src/rate_limiter.hpp" --rawfile probe "$P/probe.cpp" \
  '{"rate_limiter.hpp": $hdr, "probe.cpp": $probe}' > "$tmp/cpp.json"
check cpp "$tmp/cpp.json"

echo "All sandbox probes blocked."
