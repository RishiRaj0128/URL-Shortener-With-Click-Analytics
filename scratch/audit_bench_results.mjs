import fs from 'fs';
import path from 'path';
import zlib from 'zlib';

const resultsDir = path.resolve('bench/results');
const files = fs.readdirSync(resultsDir).filter(f => f.endsWith('.json') && !f.includes('manifest') && !f.includes('summary_data'));

const audit = [];

for (const file of files) {
  const filePath = path.join(resultsDir, file);
  let content = fs.readFileSync(filePath, 'utf8');
  
  if (file.endsWith('_summary.json') || file.startsWith('summary_') || file.startsWith('k6_')) {
    // Summary file
    try {
      const json = JSON.parse(content);
      const reqCount = json.metrics?.http_reqs?.values?.count ?? json.metrics?.http_reqs?.count ?? 'N/A';
      audit.push({
        file,
        type: 'summary',
        requestCount: reqCount,
        target: 'N/A (summary)',
        statusHistogram: 'N/A'
      });
    } catch (e) {
      audit.push({ file, type: 'summary', error: e.message });
    }
  } else if (file.endsWith('.json')) {
    // Raw point stream file
    const lines = content.split('\n').filter(Boolean);
    const statuses = {};
    const urls = new Set();
    let totalReqs = 0;
    let minTime = null, maxTime = null;

    for (const l of lines) {
      try {
        const p = JSON.parse(l);
        if (p.type === 'Point') {
          if (p.data?.time) {
            const t = new Date(p.data.time).getTime();
            if (!minTime || t < minTime) minTime = t;
            if (!maxTime || t > maxTime) maxTime = t;
          }
          if (p.metric === 'http_reqs') {
            totalReqs++;
            if (p.data?.tags?.status) {
              const s = p.data.tags.status;
              statuses[s] = (statuses[s] || 0) + 1;
            }
            if (p.data?.tags?.url) {
              urls.add(p.data.tags.url);
            }
          }
        }
      } catch (e) {}
    }

    const sampleUrls = [...urls].slice(0, 3);
    const isMock = sampleUrls.some(u => u.includes(':8089'));
    const isRealRouter = sampleUrls.some(u => u.includes(':8080'));
    const isRealNode = sampleUrls.some(u => u.includes(':8081') || u.includes(':8082') || u.includes(':8083'));

    let targetDesc = 'UNKNOWN';
    if (isMock) targetDesc = '127.0.0.1:8089 (bench_server.mjs mock)';
    else if (isRealRouter) targetDesc = '127.0.0.1:8080 (real Nginx router)';
    else if (isRealNode) targetDesc = '127.0.0.1:8081 (real app node direct)';

    audit.push({
      file,
      type: 'raw',
      date: minTime ? new Date(minTime).toISOString() : 'N/A',
      requestCount: totalReqs,
      target: targetDesc,
      statusHistogram: statuses,
      sampleUrls
    });
  }
}

console.log(JSON.stringify(audit, null, 2));
fs.writeFileSync('scratch/audit_report.json', JSON.stringify(audit, null, 2));
