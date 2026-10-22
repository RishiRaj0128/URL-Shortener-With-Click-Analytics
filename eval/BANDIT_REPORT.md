# Multi-Armed Bandit (MAB) Multi-Destination A/B Link Optimization Report

> **Methodology**: Evaluated across **50 independent Monte Carlo seeds** (2,000 visitors/redirects per seed, 100,000 total simulated interactions per strategy).
> **Target Scenario**: Dynamic A/B conversion optimization across 3 destination landing page variants with preset ground truth conversion rates:
> - **Variant A (Lander V1)**: $p = 0.85$ (Optimal)
> - **Variant B (Lander V2)**: $p = 0.70$
> - **Variant C (Lander V3)**: $p = 0.50$
> - **Prior**: Uniform uninformative $\text{Beta}(1, 1)$.

---

## 1. Measured Multi-Seed Simulation Performance (Mean ± Std over 50 Seeds)

| Routing Strategy | Total Clicks / Seed | Mean Conversions (Mean ± Std) | Mean Conversion Rate | Optimal Variant Allocation (V1) | Cumulative Regret ($T \cdot \mu^* - \sum r_t$) |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Thompson Sampling (Bayesian Beta-Bernoulli)** | 2,000 | **1692.5 ± 13.7** | **84.62 ± 0.69%** | **96.95 ± 2.1%** (1938.9 / 2,000) | **11.7 ± 6.94** |
| **Epsilon-Greedy ($\epsilon = 0.10$)** | 2,000 | 1659.3 ± 26.2 | 82.96 ± 1.31% | 91.01 ± 5.29% (1820.2 / 2,000) | 40.99 ± 16.42 |
| **Uniform Random (Baseline)** | 2,000 | 1374.4 ± 19.6 | 68.72 ± 0.98% | 33.31 ± 1.12% (666.1 / 2,000) | 332.81 ± 6.61 |

---

## 2. Engineering Findings & Honest Discussion

1. **Simulation Bounds & Assumptions**:
   - These numbers represent an in-silico Monte Carlo simulation with stationary conversion probabilities ($0.85, 0.70, 0.50$).
   - In production environments, conversion feedback is delayed (visitors convert hours or days after click), and conversion rates may experience non-stationary drift.
2. **Regret Comparison**:
   - Thompson Sampling achieved a mean cumulative regret of **11.7**, compared to **332.81** under static uniform splitting (**96.5% regret reduction in simulation**).
   - Epsilon-greedy incurs a constant linear regret penalty due to persistent random exploration even after statistical convergence.
3. **Algorithmic Overhead**:
   - The Marsaglia & Tsang Beta sampler executes in $< 2\mu\text{s}$ per decision, keeping routing overhead well below the network RTT.
