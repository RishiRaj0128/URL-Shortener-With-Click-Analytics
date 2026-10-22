import { execSync, spawn } from 'child_process';
import http from 'http';

console.log('=================================================================');
console.log('FAULT INJECTION DRILL: Redis Crash Mid-Load & Automatic Recovery');
console.log('=================================================================');

// Check initial state
console.log('1. Checking initial consumer group status in Redis...');
try {
  const initGroups = execSync('docker exec urlshortener-redis redis-cli XINFO GROUPS clicks:stream', { encoding: 'utf8' });
  console.log(initGroups.trim());
} catch (e) {
  console.log('Init groups error:', e.message);
}

// Target URL
const TARGET_URL = 'http://localhost:8000/bench_warm_1';

let totalRequests = 0;
let successes = 0;
let redirect302 = 0;
let errors = 0;
let running = true;

// Continuous traffic generator: 40 requests per second
console.log('\n2. Starting continuous redirect traffic generation (40 RPS)...');
const interval = setInterval(async () => {
  if (!running) return;
  for (let i = 0; i < 4; i++) {
    totalRequests++;
    const req = http.get(TARGET_URL, { timeout: 2000 }, (res) => {
      res.resume();
      if (res.statusCode === 302 || res.statusCode === 200) {
        successes++;
        redirect302++;
      } else {
        successes++;
      }
    });
    req.on('error', (err) => {
      errors++;
    });
  }
}, 100);

// Wait 5 seconds, then stop Redis
setTimeout(() => {
  console.log('\n>>> [FAULT INJECTION] Stopping Redis container urlshortener-redis mid-load...');
  try {
    execSync('docker stop urlshortener-redis', { encoding: 'utf8' });
    console.log('>>> Redis container STOPPED successfully.');
  } catch (err) {
    console.error('Failed to stop Redis:', err.message);
  }

  // Keep Redis down for 6 seconds, then restart it
  setTimeout(() => {
    console.log('\n>>> [RECOVERY PHASE] Restarting Redis container urlshortener-redis...');
    try {
      execSync('docker start urlshortener-redis', { encoding: 'utf8' });
      console.log('>>> Redis container RESTARTED successfully.');
    } catch (err) {
      console.error('Failed to restart Redis:', err.message);
    }

    // Wait 8 seconds for recovery and lag drain
    setTimeout(() => {
      running = false;
      clearInterval(interval);

      console.log('\n3. Drill completed. Verifying recovery metrics...');
      console.log(`Total Requests Sent: ${totalRequests}`);
      console.log(`Successful Redirects (302/200): ${redirect302}`);
      console.log(`Errors During Outage: ${errors}`);

      // Check Redis consumer group status post-recovery
      setTimeout(() => {
        try {
          const postGroups = execSync('docker exec urlshortener-redis redis-cli XINFO GROUPS clicks:stream', { encoding: 'utf8' });
          console.log('\nPost-Recovery XINFO GROUPS:');
          console.log(postGroups.trim());

          const postConsumers = execSync('docker exec urlshortener-redis redis-cli XINFO CONSUMERS clicks:stream clicks:consumer-group', { encoding: 'utf8' });
          console.log('\nPost-Recovery XINFO CONSUMERS:');
          console.log(postConsumers.trim());
        } catch (e) {
          console.error('Failed to read post-recovery status:', e.message);
        }
      }, 3000);

    }, 8000);
  }, 6000);
}, 5000);
