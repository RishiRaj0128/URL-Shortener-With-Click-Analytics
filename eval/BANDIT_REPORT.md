# Multi-Armed Bandit (MAB) Multi-Destination A/B Link Optimization Report

> **Target Scenario**: Dynamic A/B conversion optimization across 3 destination landing page variants with uncertain conversion rates.
> - **Variant A (Lander V1)**: Ground truth conversion rate $p = 0.85$ (Optimal)
> - **Variant B (Lander V2)**: Ground truth conversion rate $p = 0.70$
> - **Variant C (Lander V3)**: Ground truth conversion rate $p = 0.50$
> - **Horizon**: 2,000 visitors/redirects per routing strategy.

---

## 1. Measured Performance Comparison

| Routing Algorithm | Total Redirects | Total Conversions | Overall Conversion Rate | Optimal Variant Allocation (V1) | Cumulative Regret ($T \cdot \mu^* - \sum r_t$) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Thompson Sampling (Bayesian Beta-Bernoulli)** | 2,000 | **1659** | **82.95%** | **93.4%** (1868 / 2,000) | **23.4** |
| **Epsilon-Greedy ($\epsilon = 0.10$)** | 2,000 | 1628 | 81.4% | 92.15% (1843 / 2,000) | 38.55 |
| **Uniform Random (Baseline)** | 2,000 | 1384 | 69.2% | 34.4% (688 / 2,000) | 322.2 |

---

## 2. Per-Variant Breakdown

### A. Thompson Sampling Allocations
- **Variant-A (Lander V1)**: 1868 routed (93.4%), 1564 conversions (83.7% empirical) | $\alpha=1565, \beta=305$
- **Variant-B (Lander V2)**: 114 routed (5.7%), 85 conversions (74.6% empirical) | $\alpha=86, \beta=30$
- **Variant-C (Lander V3)**: 18 routed (0.9%), 10 conversions (55.6% empirical) | $\alpha=11, \beta=9$

### B. Epsilon-Greedy Allocations
- **Variant-A (Lander V1)**: 1843 routed (92.2%), 1540 conversions (83.6% empirical)
- **Variant-B (Lander V2)**: 82 routed (4.1%), 59 conversions (72.0% empirical)
- **Variant-C (Lander V3)**: 75 routed (3.8%), 29 conversions (38.7% empirical)

### C. Uniform Random Allocations
- **Variant-A (Lander V1)**: 688 routed (34.4%), 599 conversions (87.1% empirical)
- **Variant-B (Lander V2)**: 685 routed (34.3%), 466 conversions (68.0% empirical)
- **Variant-C (Lander V3)**: 627 routed (31.4%), 319 conversions (50.9% empirical)

---

## 3. Engineering Analysis & Discussion

1. **Why Thompson Sampling Outperforms Epsilon-Greedy**:
   - Epsilon-greedy commits a fixed 10% tax ($epsilon = 0.10$) to random exploratory traffic forever, even long after Variant A is proven superior with high statistical confidence.
   - Thompson Sampling naturally reduces exploration as posterior distributions concentrate ($alpha + eta$ grows), achieving **logarithmic asymptotic regret** $O(\log T)$ instead of linear regret $O(T \epsilon)$.
2. **Exploration vs Exploitation Tradeoff**:
   - Thompson Sampling allows continuous adaptation: if an underperforming landing page is updated and begins converting better, samples from wider distributions will occasionally draw high $\theta$ values, giving updated variants traffic without manual intervention.
3. **Execution Latency**:
   - Marsaglia & Tsang Gamma generator computes Beta samples in $< 0.002$ ms in pure Java/JS, making it viable on high-throughput redirect paths.
