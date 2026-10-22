# Diagnostic script to capture all bottleneck metrics together during 200 RPS load
$ErrorActionPreference = "Continue"

Write-Host "Starting 200 RPS k6 load for 30s..."
$k6Process = Start-Process docker -ArgumentList "run --rm --network urlshortenerwithclickanalytics_default -v `"${PWD}:/workspace`" -w /workspace -e RATE=200 -e DURATION=30s -e PRE_ALLOCATED_VUS=100 -e OUTPUT_FILE=/workspace/bench/results/bottleneck_k6_run.json grafana/k6:latest run bench/k6_in_network.js" -PassThru -NoNewWindow

Start-Sleep -Seconds 12

Write-Host "Capturing live telemetry under load at second 12-15..."

# 1. Docker stats
$dockerStats = docker stats --no-stream --format "json" urlshortener-node-1 | Out-String

# 2. Cgroup throttled periods
$cpuStat = docker exec urlshortener-node-1 cat /sys/fs/cgroup/cpu.stat | Out-String

# 3. Tomcat & Hikari metrics via Actuator Prometheus
$promMetrics = docker run --rm --network urlshortenerwithclickanalytics_default curlimages/curl:latest -s http://app-1:8081/actuator/prometheus | Out-String

# 4. Trigger thread dump
docker exec urlshortener-node-1 kill -3 1
Start-Sleep -Seconds 2
$threadDump = docker logs --tail 300 urlshortener-node-1 | Out-String

# 5. Redis latency info
$redisInfo = docker exec urlshortener-redis redis-cli info stats | Out-String

$k6Process.WaitForExit()

# Save thread dump
$threadDump | Out-File -FilePath "bench/results/jstack_200rps.txt" -Encoding utf8

# Save collected metrics
$telemetry = @{
    docker_stats = $dockerStats.Trim()
    cgroup_cpu_stat = $cpuStat.Trim()
    prometheus_metrics = $promMetrics
    redis_info_stats = $redisInfo.Trim()
}

$telemetry | ConvertTo-Json -Depth 5 | Out-File -FilePath "bench/results/bottleneck_telemetry_200rps.json" -Encoding utf8
Write-Host "Telemetry captured successfully."
