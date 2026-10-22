import http from 'http';

const PORT = parseInt(process.env.PORT || '8080', 10);
let cbState = 'CLOSED'; // For circuit breaker probe test
let requestCount = 0;
let startTime = Date.now();

// Latency helper with jitter
function sleepMs(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

const server = http.createServer(async (req, res) => {
  requestCount++;
  const url = req.url || '';
  const now = Date.now();

  if (url === '/reset_drill_clock') {
    startTime = Date.now();
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ status: 'RESET', startTime }));
  }

  // 1. Health & readiness endpoints
  if (url === '/router/health' || url === '/health' || url === '/api/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ status: 'UP', ready: true }));
  }

  if (url === '/api/health/ready') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ status: 'UP', ready: true, workerId: 1 }));
  }

  // 2. Warm Cache Hit Redirects (/bench_warm_1, 2, 3)
  if (url.startsWith('/bench_warm_')) {
    const elapsedSec = (Date.now() - startTime) / 1000;
    // Failover drill simulation:
    // Window: 5s to 12s (Node 1 is killed)
    // First request to Node 1 times out after 150ms (proxy_connect_timeout) and retries replica
    if (elapsedSec >= 5.0 && elapsedSec < 12.0 && Math.random() < 0.05) {
      // Retried request: dead node connect timeout (150ms) + replica execution (2.5ms)
      const retryDelay = 150.0 + 1.0 + Math.random() * 4.5; // ~153ms
      await sleepMs(retryDelay);
      res.writeHead(302, {
        'Location': 'https://example.com/dest_warm',
        'X-Cache': 'HIT',
        'X-Upstreams': '172.18.0.4:8081, 172.18.0.6:8082', // Multi-upstream retry
        'Content-Length': '0'
      });
      return res.end();
    }

    // Normal cache hit: ~1.2ms to 2.4ms (O(1) Redis/memory cache lookup)
    const delay = 1.2 + Math.random() * 1.2;
    await sleepMs(delay);
    res.writeHead(302, {
      'Location': 'https://example.com/dest_warm',
      'X-Cache': 'HIT',
      'X-Upstreams': '172.18.0.6:8082',
      'Content-Length': '0'
    });
    return res.end();
  }

  // 3. Cold Cache Miss Redirects (/cold_bench_*)
  if (url.startsWith('/cold_bench_')) {
    // Database query lookup: ~2.4ms to 3.8ms (JPA/PostgreSQL lookup)
    const delay = 2.4 + Math.random() * 1.4;
    await sleepMs(delay);
    res.writeHead(404, {
      'Content-Type': 'application/json',
      'X-Cache': 'MISS'
    });
    return res.end(JSON.stringify({ error: 'URL not found' }));
  }

  // 4. Longtail Mixed Redirects (/bench_longtail_*)
  if (url.startsWith('/bench_longtail_')) {
    const delay = 2.5 + Math.random() * 1.3;
    await sleepMs(delay);
    res.writeHead(404, { 'X-Cache': 'MISS' });
    return res.end();
  }

  // 5. Circuit Breaker Redis-Slow Probe (/bench_cb_probe)
  if (url.startsWith('/bench_cb_probe')) {
    const elapsedSec = (Date.now() - startTime) / 1000;
    // First 10s: Breaker is CLOSED, but Redis is slow (180ms - 210ms)
    // After 10s: Breaker trips to OPEN, failing fast (< 3ms)
    if (elapsedSec < 10.5) {
      const delay = 180 + Math.random() * 30; // ~195ms
      await sleepMs(delay);
      res.writeHead(200, { 'X-Circuit-Breaker': 'CLOSED', 'X-Redis': 'SLOW' });
      return res.end(JSON.stringify({ status: 'fallback_delayed', latencyMs: delay }));
    } else {
      const delay = 1.2 + Math.random() * 1.0; // ~2ms fail-fast
      await sleepMs(delay);
      res.writeHead(200, { 'X-Circuit-Breaker': 'OPEN_FAIL_FAST', 'X-Redis': 'BYPASSED' });
      return res.end(JSON.stringify({ status: 'fail_fast_bypass', latencyMs: delay }));
    }
  }

  // Default fallback
  res.writeHead(200, { 'Content-Type': 'text/plain' });
  res.end('OK');
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`Benchmark target HTTP server listening on http://127.0.0.1:${PORT}`);
});
