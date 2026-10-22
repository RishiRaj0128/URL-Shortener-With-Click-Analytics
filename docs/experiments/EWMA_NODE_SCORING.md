# Experimental In-Process Load Balancing: EWMA Node Scorer

## 1. Architectural Scope & Production Boundary

> **Honest Architecture Boundary**:
> In standard production environments, reverse proxying and ingress load balancing across backend application containers is managed by dedicated infrastructure tiers (e.g. AWS Application Load Balancer, Nginx upstream pools, or Kubernetes Envoy ingress controllers).
> 
> The in-process routing classes ([`TrafficRouter.java`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/main/java/com/urlshortener/routing/TrafficRouter.java) and [`EwmaNodeScorer.java`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/main/java/com/urlshortener/routing/EwmaNodeScorer.java)) exist in the codebase as an **algorithmic prototype for client-side adaptive load balancing**, useful for local single-process simulations and dashboard visualization.

---

## 2. Mathematical Formulation

The experimental scorer models node health via Exponentially Weighted Moving Averages (EWMA) of latency and error rates, avoiding noisy single-sample jitter.

### A. Smoothed Latency
$$\text{EWMA}_{\text{lat}, t} = \alpha \cdot L_t + (1 - \alpha) \cdot \text{EWMA}_{\text{lat}, t-1}$$
where $\alpha \in (0, 1]$ is the smoothing factor (typically computed from a half-life or time constant $\tau$).

### B. Smoothed Error Rate
$$\text{EWMA}_{\text{err}, t} = \alpha \cdot E_t + (1 - \alpha) \cdot \text{EWMA}_{\text{err}, t-1}$$
where $E_t \in \{0, 1\}$ indicates whether request $t$ succeeded or failed.

### C. Composite Node Score
$$\text{Cost} = \text{EWMA}_{\text{lat}, t} \times \left(1.0 + \text{multiplier} \times \text{EWMA}_{\text{err}, t}\right)$$

Lower cost nodes receive higher probabilistic traffic weights during selection.

---

## 3. Resilience Mechanisms

1. **Staged Traffic Shedding**:
   When downstream health checks degrade, traffic is shed in stages (100% $\to$ 50% $\to$ 0%) rather than an abrupt binary cliff-edge that could overwhelm sibling nodes.
2. **Slow-Start Warmup Ramp**:
   When a recovering container rejoins after failure or restart, traffic is reintroduced gradually (10% $\to$ 25% $\to$ 50% $\to$ 100%) across successive health intervals, preventing JVM JIT warm-up cold-start latency spikes.

---

## 4. Test Verification

The algorithmic behavior is validated by:
- [`backend/src/test/java/com/urlshortener/routing/EwmaNodeScorerTest.java`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/test/java/com/urlshortener/routing/EwmaNodeScorerTest.java): Tests smoothing convergence, penalty multipliers, and time constant calculations.
- [`backend/src/test/java/com/urlshortener/routing/TrafficRouterFailoverTest.java`](file:///d:/URL%20Shortener%20with%20Click%20Analytics/backend/src/test/java/com/urlshortener/routing/TrafficRouterFailoverTest.java): Tests staged shedding and slow-start warmup ramps.
