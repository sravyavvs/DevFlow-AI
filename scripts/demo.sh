#!/usr/bin/env bash
# Runs every assessment scenario against a running app and saves evidence to docs/runs/. Requires curl and jq.
set -euo pipefail
BASE=http://localhost:8080/api/pipelines
OUT="$(cd "$(dirname "$0")/.." && pwd)/docs/runs"
mkdir -p "$OUT"
command -v jq >/dev/null || { echo "jq is required"; exit 1; }

post() { curl -sf -X POST "$BASE$1" -H 'Content-Type: application/json' -d "$2"; }
approve() { post "/$1/stages/$2/approval" "{\"decision\":\"approve\",\"reviewerId\":\"$3\",\"reviewerNote\":\"Reviewed $2\"}"; }
expect() { [ "$1" = "$2" ] && echo "  ok  $3 = $1" || { echo "FAIL [$3]: expected $2, got $1"; exit 1; }; }
save() {
  curl -sf "$BASE/$2" | jq . > "$OUT/$1-pipeline.json"
  for part in events artifacts metrics graph; do curl -sf "$BASE/$2/$part" | jq . > "$OUT/$1-$part.json"; done
}
run_full() { # name requirement
  local p id; p=$(post "" "{\"requirement\":\"$2\"}"); id=$(jq -r .pipelineId <<<"$p")
  approve "$id" IMPLEMENTATION tech-lead >/dev/null
  p=$(approve "$id" RELEASE_READINESS release-manager)
  expect "$(jq -r .status <<<"$p")" COMPLETED "$1"; save "$1" "$id"
}

echo "1) GREENFIELD";  run_full greenfield "Build a URL shortener API that creates short links, redirects users, and tracks click analytics."
echo "2) BROWNFIELD";  run_full brownfield "Fix the existing URL shortener so unknown short codes return 404 and add regression tests."

echo "3) AMBIGUOUS"
p=$(post "" '{"requirement":"Make the URL shortener faster and more secure for users."}'); id=$(jq -r .pipelineId <<<"$p")
expect "$(jq -r .status <<<"$p")" NEEDS_CLARIFICATION "ambiguous held"
p=$(post "/$id/clarification" '{"requesterId":"product-owner","clarification":"Fix the existing URL shortener so unknown short codes return 404 and add regression checks; defer performance and security improvements."}')
expect "$(jq -r .status <<<"$p")" AWAITING_APPROVAL "re-planned"
approve "$id" IMPLEMENTATION tech-lead >/dev/null; p=$(approve "$id" RELEASE_READINESS release-manager)
expect "$(jq -r .status <<<"$p")" COMPLETED "ambiguous (clarified)"; save ambiguous "$id"

echo "4) UNMEASURABLE QUALITY GOAL"
p=$(post "" '{"requirement":"Build a secure URL shortener that creates short links and redirects users."}'); id=$(jq -r .pipelineId <<<"$p")
p=$(approve "$id" IMPLEMENTATION tech-lead); expect "$(jq -r .status <<<"$p")" BLOCKED "release blocked"; save blocked-nfr "$id"

echo "5) FAILURE AND RECOVERY"
p=$(post "" '{"requirement":"Build a URL shortener API that creates short links, redirects users, and tracks click analytics."}'); id=$(jq -r .pipelineId <<<"$p")
post "/$id/faults" '{"stageId":"TESTING","mode":"TRANSIENT_ONCE"}' >/dev/null
approve "$id" IMPLEMENTATION tech-lead >/dev/null
post "/$id/faults" '{"stageId":"RELEASE_READINESS","mode":"PERMANENT"}' >/dev/null
p=$(approve "$id" RELEASE_READINESS release-manager); expect "$(jq -r .status <<<"$p")" FAILED "halted"
p=$(post "/$id/resume" '{"operatorId":"on-call-engineer","reason":"Fault cleared"}'); expect "$(jq -r .status <<<"$p")" COMPLETED "recovered"
curl -sf "$BASE/$id/metrics" | jq '{retryCount, rollbackCount, mttrMs, stageSuccessRatePercent, endToEndLatencyMs}'
save failure-recovery "$id"
echo "All scenarios passed. Evidence in docs/runs/"
