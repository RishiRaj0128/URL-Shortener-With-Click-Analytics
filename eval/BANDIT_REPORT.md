# Multi-Armed Bandit (MAB) Routing Evaluation Report

> **Target Scenario**: Payment Gateway Routing Optimization across 3 bank gateways with uncertain / fluctuating success rates.
> - **Gateway A (HDFC)**: Ground truth $p = 0.85$ (Optimal)
> - **Gateway B (ICICI)**: Ground truth $p = 0.70$
> - **Gateway C (Axis)**: Ground truth $p = 0.50$
> - **Horizon**: 2,000 transactions per routing strategy.

---

## 1. Measured Performance Comparison

| Routing Algorithm | Total Transactions | Total Conversions | Overall Success Rate | Optimal Gateway Allocation (HDFC) | Cumulative Regret ($T \cdot \mu^* - \sum r_t$) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Thompson Sampling (Bayesian Beta-Bernoulli)** | 2,000 | **1706** | **85.3%** | **97.9%** (1957 / 2,000) | **8.3** |
| **Epsilon-Greedy ($\epsilon = 0.10$)** | 2,000 | 1650 | 82.5% | 93.3% (1866 / 2,000) | 33.9 |
| **Uniform Random (Baseline)** | 2,000 | 1336 | 66.8% | 32.5% (649 / 2,000) | 348.9 |

---

## 2. Per-Gateway Breakdown

### A. Thompson Sampling Allocations
- **Gateway-A (HDFC)**: 1957 routed (97.9%), 1680 successes (85.8% empirical) | $\alpha=1681, \beta=278$
- **Gateway-B (ICICI)**: 34 routed (1.7%), 23 successes (67.6% empirical) | $\alpha=24, \beta=12$
- **Gateway-C (Axis)**: 9 routed (0.4%), 3 successes (33.3% empirical) | $\alpha=4, \beta=7$

### B. Epsilon-Greedy Allocations
- **Gateway-A (HDFC)**: 1866 routed (93.3%), 1565 successes (83.9% empirical)
- **Gateway-B (ICICI)**: 65 routed (3.3%), 48 successes (73.8% empirical)
- **Gateway-C (Axis)**: 69 routed (3.5%), 37 successes (53.6% empirical)

### C. Uniform Random Allocations
- **Gateway-A (HDFC)**: 649 routed (32.5%), 539 successes (83.1% empirical)
- **Gateway-B (ICICI)**: 620 routed (31.0%), 429 successes (69.2% empirical)
- **Gateway-C (Axis)**: 731 routed (36.5%), 368 successes (50.3% empirical)

---

## 3. Systems & Interview Insights (Juspay / Payments Context)

1. **Why Thompson Sampling Outperforms Epsilon-Greedy**:
   - Epsilon-greedy commits a fixed 10% tax ($epsilon = 0.10$) to random exploratory traffic forever, even long after Gateway A is proven superior with high statistical confidence.
   - Thompson Sampling naturally reduces exploration as posterior distributions concentrate ($alpha + eta$ grows), achieving **logarithmic asymptotic regret** $O(\log T)$ instead of linear regret $O(T \epsilon)$.
2. **Exploration vs Exploitation Tradeoff**:
   - Thompson Sampling allows rapid re-discovery if an underperforming bank gateway heals: samples from wider distributions will occasionally draw high $\theta$ values, giving degraded gateways a probe request without manual operator intervention.
3. **Execution Latency**:
   - Marsaglia & Tsang Gamma generator computes Beta samples in $< 0.002$ ms in pure code, making it fully viable on the sub-millisecond hot payment redirect path.
