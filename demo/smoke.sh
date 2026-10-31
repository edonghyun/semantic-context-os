#!/usr/bin/env bash
# Runs the demo Quickstart end to end with the same commands as the README, then evaluates search quality against the demo
# judgment pack; CI runs this script on an empty runner. Steps 1–5 need Docker and Python 3; step 6 also needs a JDK.
# Exits non-zero when any step fails, the search returns no hits, or a metric falls below its floor. On failure it prints the compose logs.
# Always removes the containers and both volumes (`--profile tools down -v`), pass or fail.
# Set COMPOSE_PROJECT_NAME to run it beside a demo loaded by hand (otherwise that demo's volumes are removed too).
set -euo pipefail

cd "$(dirname "$0")/.."

# The scos containers run with `run --rm`, so `compose logs` cannot show them; step 6 keeps its server's stderr here.
EVAL_SERVER_LOG=harness/build/eval/demo/server-stderr.log
rm -f "$EVAL_SERVER_LOG"

cleanup() {
  local status=$?
  if [ "$status" -ne 0 ]; then
    echo "smoke: failed with exit $status; compose logs follow" >&2
    docker compose -f demo/compose.yaml --profile tools logs --no-color >&2 || true
    if [ -s "$EVAL_SERVER_LOG" ]; then
      echo "smoke: stderr of the MCP server evaluated in step 6 follows" >&2
      cat "$EVAL_SERVER_LOG" >&2 || true
    fi
  fi
  docker compose -f demo/compose.yaml --profile tools down -v || true
  exit "$status"
}
trap cleanup EXIT

step() { echo "smoke: $*" >&2; }

step "1/6 build the server image"
docker compose -f demo/compose.yaml build scos

step "2/6 start PostgreSQL and the embedder"
docker compose -f demo/compose.yaml up -d --wait

step "3/6 embed the demo corpus"
docker compose -f demo/compose.yaml run --rm corpus

step "4/6 import the corpus"
docker compose -f demo/compose.yaml run --rm scos import --file=/work/units.embedded.json --space=demo --embedding-generation=openai-compatible:scos-demo-hashing:768:title+aliases+content

step "5/6 search over MCP"
python3 demo/mcp_search.py "결제 중복 승인 장애 원인" --min-hits 1 -- docker compose -f demo/compose.yaml run --rm -T scos mcp

# Floors are the demo's measured values rounded down to 0.05 (recall 0.787, MRR 0.944, nDCG 0.818 at k=5; see demo/README.md).
# The hashing embedder is deterministic, so a drop below a floor means search behaviour changed.
step "6/6 evaluate against the judgment pack (needs a JDK)"
(cd harness && ./gradlew evaluate --console=plain -Pevaluate.args="--pack ../demo/eval/ottercart-demo-pack.json --server-command ../demo/mcp-server.sh --out build/eval/demo --k 5 --min-recall 0.75 --min-mrr 0.90 --min-ndcg 0.80")

step "passed"
