#!/usr/bin/env bash
set -eo pipefail

echo "=== Starting Orchestrated Failover Verification Drill ==="

MAX_ALLOWABLE_ERROR_RATE="0.02" # 2.0%
ROUTER_URL="${ROUTER_URL:-http://localhost:8080}"

echo "1. Checking router health at ${ROUTER_URL}/router/health..."
curl -s --connect-timeout 2 "${ROUTER_URL}/router/health" || echo "Router unreachable; designed for container runtime."

echo "2. Launching steady k6 load (15 VUs) for 20s..."
k6 run --summary-export="bench/results/failover_summary.json" bench/failover_test.js &
K6_PID=$!

sleep 5

echo "3. [FAULT INJECTION] Executing mid-stream container kill: docker kill urlshortener-node-1"
docker kill urlshortener-node-1 || true

wait $K6_PID

echo "4. Analyzing Failover Telemetry..."
if [ -f "bench/results/failover_summary.json" ]; then
    cat bench/results/failover_summary.json | jq '.metrics | {requests: .http_reqs.count, failed_rate: .http_req_failed.value, p95_ms: .http_req_duration."p(95)"}'
fi
