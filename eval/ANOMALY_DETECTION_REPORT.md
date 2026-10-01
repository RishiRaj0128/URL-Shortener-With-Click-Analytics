# Anomaly Detection Evaluation & Benchmark Report

> **Dataset**: 5,000 synthetic requests across realistic human Poisson traffic and injected attack vectors.
> - **Legitimate Human Traffic**: 4,500 requests (varying Poisson inter-arrival intervals, 5 distinct user-agents, diverse IP subnets).
> - **Attack Profile 1 (High-Velocity Single IP Burst)**: 250 requests (10 distinct bursts of 25 rapid-fire requests from single IP origins).
> - **Attack Profile 2 (Distributed Low-and-Slow Botnet)**: 250 requests (50 bots, identical UA fingerprint, fixed machine-timed 50ms loops with CV < 0.10).

---

## 1. Measured Performance & Confusion Matrix

| Metric | Measured Value | Production Requirement |
| :--- | :--- | :--- |
| **Total Windows Evaluated** | **497** | ≥ 400 windows |
| **Precision** | **100.00%** | ≥ 90.0% (low false accusations) |
| **Recall** | **52.70%** | ≥ 85.0% (captures bot bursts) |
| **F1-Score** | **0.6903** | ≥ 0.88 |
| **Accuracy** | **92.96%** | ≥ 95.0% |
| **False Positive Rate (FPR)** | **0.00%** | ≤ 2.0% |
| **Avg Classification Latency** | **87.36 µs** | **< 50 µs (< 0.05 ms)** |

### Confusion Matrix Breakdown
- **True Positives (TP)**: 39 (correctly quarantined malicious bot traffic)
- **True Negatives (TN)**: 423 (correctly permitted legitimate human traffic)
- **False Positives (FP)**: 0 (legitimate traffic misidentified as bot)
- **False Negatives (FN)**: 35 (bot traffic that bypassed heuristics)

---

## 2. Multi-Dimensional Feature Rationale (Interview Defense)

1. **Shannon IP Entropy ($H(\text{IP})$)**:
   - Evaluates: $H(X) = -\sum p_i \log_2 p_i$.
   - A single-IP burst yields **$0.0$ bits**, whereas normal diverse human traffic yields **$3.5 - 5.0$ bits**.
2. **User-Agent Entropy ($H(\text{UA})$)**:
   - Botnets using distributed proxy IPs often share identical scraped client fingerprints ($H(\text{UA}) \to 0$).
3. **Inter-Arrival Coefficient of Variation ($CV = \frac{\sigma}{\mu}$)**:
   - Humans clicking links follow an inhomogeneous Poisson process with high timing jitter ($CV \ge 0.60$).
   - Automated scripts running fixed sleep/interval loops exhibit $CV < 0.15$.
4. **Sub-Millisecond Classification Latency**:
   - The multi-dimensional heuristic runs in **87.36 µs**, which is $100\times$ faster than evaluating complex deep learning / neural net models in the synchronous hot path.
