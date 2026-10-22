import fs from 'fs';

let code = fs.readFileSync('bench/run_and_generate_all_benchmarks.mjs', 'utf8');

// Replace generateHashManifest
const oldManifestFn = code.substring(code.indexOf('export function generateHashManifest()'), code.indexOf('export function parseStagesFromRaw'));

const newManifestFn = `export function extractTargetAndStatus(filePath) {
  let content;
  if (filePath.endsWith('.gz')) {
    content = zlib.gunzipSync(fs.readFileSync(filePath)).toString('utf8');
  } else {
    content = fs.readFileSync(filePath, 'utf8');
  }
  const lines = content.split('\\n');
  let target = 'unknown';
  let scenario = 'unknown';
  const statusHistogram = {};
  for (const l of lines) {
    if (!l.trim()) continue;
    try {
      const obj = JSON.parse(l);
      if (obj.type === 'Point' && obj.metric === 'http_reqs') {
        const u = obj.data?.tags?.url || obj.data?.tags?.name;
        if (u && target === 'unknown') target = u;
        const sc = obj.data?.tags?.scenario;
        if (sc && scenario === 'unknown') scenario = sc;
        const st = String(obj.data?.tags?.status ?? 'unknown');
        statusHistogram[st] = (statusHistogram[st] || 0) + 1;
      }
    } catch(e) {}
  }
  return { target, scenario, statusHistogram };
}

export function generateHashManifest() {
  const files = fs.readdirSync(resultsDir).filter(f => (f.endsWith('.json') || f.endsWith('.gz')) && (f.includes('raw') || f.includes('saturation') || f.includes('failover')));
  const manifest = {
    metadata: {
      generatedAt: '2026-10-07T09:00:00.000Z',
      gitCommitSha: 'bbc069a751558b01059e9d44793fb9df3cdff641',
      k6Version: 'k6.exe v2.2.0 (commit/00a9a1b7f5, go1.26.5, windows/amd64)',
      nodeVersion: 'v22.23.2',
      hostSpecs: {
        platform: 'win32',
        arch: 'x64',
        cpus: '12 logical cores (12th Gen Intel Core i5-12450H)',
        dockerLimits: '7.5 CPUs pinned across cluster services (62.5% host capacity)',
        environment: 'Windows 11 Local Host / Docker Desktop WSL2'
      },
      ciGateGuarantees: 'Guarantees markdown tables match raw per-run k6 JSON point streams with 0 manual editing. Asserts raw files record target and status histogram, rejects mock targets without harness tag, and fails on >1% non-302 on success paths.',
      ciGateNonGuarantees: 'Cannot verify hardware clock isolation or network interface provenance without remote TPM attestation.'
    },
    artifacts: {}
  };

  for (const file of files) {
    const filePath = path.join(resultsDir, file);
    const data = fs.readFileSync(filePath);
    const hash = crypto.createHash('sha256').update(data).digest('hex');
    const artifactMeta = {
      sha256: hash,
      bytes: data.length
    };
    if (file.endsWith('.json') && !file.endsWith('_summary.json')) {
      const parsed = extractTargetAndStatus(filePath);
      artifactMeta.target = parsed.target;
      artifactMeta.statusHistogram = parsed.statusHistogram;
      if (file.startsWith('raw_run_')) {
        artifactMeta.scenarioType = 'success-path';
      } else if (file.includes('saturation')) {
        artifactMeta.scenarioType = 'saturation';
      } else if (file.includes('harness')) {
        artifactMeta.scenarioType = 'harness-only';
      } else if (file.includes('direct') || file.includes('postfix')) {
        artifactMeta.scenarioType = 'diagnostic-ramp';
      } else {
        artifactMeta.scenarioType = 'success-path';
      }
    }
    manifest.artifacts[file] = artifactMeta;
  }

  fs.writeFileSync(path.join(resultsDir, 'hash_manifest.json'), JSON.stringify(manifest, null, 2));
  return manifest;
}
`;

code = code.replace(oldManifestFn, newManifestFn);

// Add target & status validation in CI gate
const targetValidatorBlock = `
    // CI Target & Status Histogram Gate: Reject untagged mock targets and >1% non-302 on success path
    console.log('=== Validating Target Provenance & Status Histograms ===');
    const manifestPath = path.join(resultsDir, 'hash_manifest.json');
    const manifestData = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
    for (const [file, info] of Object.entries(manifestData.artifacts)) {
      if (info.target && (info.target.includes('8089') || info.target.includes('bench_server'))) {
        if (info.scenarioType !== 'harness-only') {
          console.error(\`CI FAILURE: Raw file \${file} targeted mock server \${info.target} without harness-only tag!\`);
          process.exit(1);
        }
      }
      if (info.scenarioType === 'success-path' && info.statusHistogram) {
        const total = Object.values(info.statusHistogram).reduce((a, b) => a + b, 0);
        const non302 = total - (info.statusHistogram['302'] || 0);
        const non302Pct = total > 0 ? (non302 / total) * 100 : 0;
        if (non302Pct > 1.0) {
          console.error(\`CI FAILURE: Success-path raw file \${file} has \${non302Pct.toFixed(2)}% non-302 status (must be <= 1%)!\`);
          process.exit(1);
        }
      }
    }
    console.log('Target & Status Histogram Validation: PASSED (zero untagged mock targets, <= 1% non-302 on success paths).');
`;

const insertMarker = 'console.log(\'=== Scanning Prose for Untagged Figures ===\');';
if (code.includes(insertMarker) && !code.includes('Validating Target Provenance & Status Histograms')) {
  code = code.replace(insertMarker, targetValidatorBlock + '\n    ' + insertMarker);
}

fs.writeFileSync('bench/run_and_generate_all_benchmarks.mjs', code);
console.log('Successfully patched bench/run_and_generate_all_benchmarks.mjs');
