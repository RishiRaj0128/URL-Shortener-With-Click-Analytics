import assert from 'assert';

const CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

function decodeBase62(str) {
  let result = 0n;
  for (const c of str) {
    const idx = CHARSET.indexOf(c);
    if (idx === -1) throw new Error(`Invalid Base62 char: ${c}`);
    result = result * 62n + BigInt(idx);
  }
  return result;
}

function extractWorkerId(snowflakeId) {
  // (snowflakeId >> 12) & 1023
  return Number((snowflakeId >> 12n) & 1023n);
}

async function runSnowflakeClusterTest() {
  console.log('=== Snowflake Cluster Distributed Verification Drill ===');
  console.log('Target: http://127.0.0.1:8080 (via Nginx load balancer to 3 worker nodes)');

  // 1. Authenticate user
  const email = `snowflake_test_${Date.now()}@example.com`;
  const signupRes = await fetch('http://127.0.0.1:8080/api/auth/signup', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: 'Snowflake Tester', email, password: 'password123' })
  });
  
  if (!signupRes.ok) {
    throw new Error(`Signup failed: ${signupRes.status} ${await signupRes.text()}`);
  }
  const signupData = await signupRes.json();
  const token = signupData.token;
  console.log(`Authenticated test user: ${email}`);

  // 2. Generate 30 URLs across the cluster to verify worker IDs 1, 2, and 3
  const TOTAL_REQUESTS = 30;
  console.log(`Dispatching ${TOTAL_REQUESTS} URL shortening requests through router...`);

  const results = [];
  for (let i = 0; i < TOTAL_REQUESTS; i++) {
    const res = await fetch('http://127.0.0.1:8080/api/shorten', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`,
        'X-Forwarded-For': `192.168.1.${(i % 50) + 1}`
      },
      body: JSON.stringify({ url: `https://juspay.in/payments/snowflake-test/${i}` })
    });
    if (!res.ok) {
      throw new Error(`Shorten failed on request ${i}: ${res.status} ${await res.text()}`);
    }
    results.push(await res.json());
  }

  // 3. Analyze collisions and worker ID distribution
  const seenCodes = new Set();
  const workerCounts = { 1: 0, 2: 0, 3: 0 };
  const allWorkerIds = new Set();

  for (const item of results) {
    const code = item.shortCode;
    assert(!seenCodes.has(code), `Collision detected on short code: ${code}!`);
    seenCodes.add(code);

    const decodedId = decodeBase62(code);
    const workerId = extractWorkerId(decodedId);
    allWorkerIds.add(workerId);
    workerCounts[workerId] = (workerCounts[workerId] || 0) + 1;
  }

  console.log('\n--- Verification Results ---');
  console.log(`Total URLs generated : ${seenCodes.size} / ${TOTAL_REQUESTS}`);
  console.log(`Unique short codes   : ${seenCodes.size}`);
  console.log(`Collisions detected  : 0 (ZERO COLLISIONS)`);
  console.log(`Worker ID distribution across cluster:`);
  console.log(`   Worker Node 1 (app-1): ${workerCounts[1] || 0} IDs`);
  console.log(`   Worker Node 2 (app-2): ${workerCounts[2] || 0} IDs`);
  console.log(`   Worker Node 3 (app-3): ${workerCounts[3] || 0} IDs`);

  // Assertions
  assert.strictEqual(seenCodes.size, TOTAL_REQUESTS, 'Expected all short codes to be distinct');
  assert(allWorkerIds.has(1), 'Assertion failed: Worker Node 1 did not generate any IDs');
  assert(allWorkerIds.has(2), 'Assertion failed: Worker Node 2 did not generate any IDs');
  assert(allWorkerIds.has(3), 'Assertion failed: Worker Node 3 did not generate any IDs');

  console.log('\nSUCCESS: Verified distinct Snowflake worker IDs across all 3 containers with ZERO collisions!');
}

runSnowflakeClusterTest().catch((err) => {
  console.error('Snowflake cluster test failed:', err);
  process.exit(1);
});
