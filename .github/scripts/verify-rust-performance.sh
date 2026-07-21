#!/usr/bin/env bash
set -euo pipefail

manifest=native/strguard-runtime/Cargo.toml
metadata=$(cargo test --manifest-path "${manifest}" --release --locked --no-run --message-format=json)
test_binary=$(jq -rs '
  [ .[] | select(.reason == "compiler-artifact" and .profile.test and .executable != null) ]
  | last.executable // empty
' <<<"${metadata}")
test -n "${test_binary}"

metrics=$(mktemp)
trap 'rm -f "${metrics}"' EXIT
/usr/bin/time -v -o "${metrics}" \
  "${test_binary}" \
  tests::performance_shape_is_linear_for_1k_5k_10k_records \
  --ignored --exact --nocapture

rss_kib=$(awk -F: '/Maximum resident set size/ { gsub(/[[:space:]]/, "", $2); print $2 }' "${metrics}")
test -n "${rss_kib}"
test "${rss_kib}" -le 262144 || {
  echo "StrGuard release benchmark peak RSS ${rss_kib} KiB exceeds 262144 KiB" >&2
  exit 1
}
echo "StrGuard release benchmark peak RSS: ${rss_kib} KiB"
