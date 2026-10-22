import fs from 'fs';

// Read eval/generate_synthetic_traffic.mjs and extract the dataset generation and evaluation logic
const content = fs.readFileSync('eval/generate_synthetic_traffic.mjs', 'utf8');

// We can append our run length calculation and run it
const runScript = content + `

function measureRunLengthL() {
  const seeds = [6, 7, 8, 9, 10, 11, 12, 13, 14, 15];
  const allRunLengths = [];
  let totalFpWindows = 0;
  let totalNormalWindows = 0;

  for (const seed of seeds) {
    const { events } = generateDatasetForSeed(seed);
    const WINDOW_SIZE = 30;
    const STEP = 5;

    let currentRun = 0;

    for (let i = WINDOW_SIZE; i <= events.length; i += STEP) {
      const window = events.slice(i - WINDOW_SIZE, i);
      const counts = {};
      for (const e of window) counts[e.groundTruthClass] = (counts[e.groundTruthClass] || 0) + 1;
      let actualClass = 'NORMAL';
      let maxCnt = 0;
      for (const [cls, cnt] of Object.entries(counts)) {
        if (cnt > maxCnt) {
          maxCnt = cnt;
          actualClass = cls;
        }
      }

      if (actualClass === 'NORMAL') {
        totalNormalWindows++;
        const predictedClass = classifyWindow(window, TUNED_THRESHOLDS);
        const isFp = (predictedClass !== 'NORMAL');
        if (isFp) {
          totalFpWindows++;
          currentRun++;
        } else {
          if (currentRun > 0) {
            allRunLengths.push(currentRun);
            currentRun = 0;
          }
        }
      } else {
        if (currentRun > 0) {
          allRunLengths.push(currentRun);
          currentRun = 0;
        }
      }
    }
    if (currentRun > 0) {
      allRunLengths.push(currentRun);
    }
  }

  const meanL = allRunLengths.length > 0 ? (allRunLengths.reduce((a, b) => a + b, 0) / allRunLengths.length) : 0;
  console.log('MEASURED_L_RESULTS:', JSON.stringify({
    totalNormalWindows,
    totalFpWindows,
    fpRate: totalFpWindows / totalNormalWindows,
    runCounts: allRunLengths.length,
    runLengths: allRunLengths,
    meanL: Number(meanL.toFixed(2))
  }));
}

measureRunLengthL();
`;

fs.writeFileSync('scratch/run_L_eval.mjs', runScript, 'utf8');
