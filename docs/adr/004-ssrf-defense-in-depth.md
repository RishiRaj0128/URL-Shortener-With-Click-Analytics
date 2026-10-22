# ADR 004: Defense-in-Depth SSRF and Redirect Security Validation

## Status
Accepted

## Context
Public URL shorteners are attractive targets for malicious actors seeking to:
1. Probe internal networks, cloud metadata services, and local administration interfaces via Server-Side Request Forgery (SSRF).
2. Create recursive self-referencing redirect loops that exhaust server resources and browser call stacks.
3. Obfuscate phishing schemes or malicious protocols (e.g. `javascript:`, `file:///`, `gopher://`).

Simple regex checks on incoming URLs are insufficient because attackers can bypass regex via IP encoding tricks (octal, hex, dword, 0.0.0.0, DNS rebinding, or private domains resolving to internal subnets).

## Decision
We implemented a multi-layered defense-in-depth URL validator in `UrlSafetyValidator.java`:

1. **Protocol Strictness**:
   - Only `http` and `https` schemes are accepted.
   - All other URI schemes (`ftp`, `file`, `javascript`, `data`, `gopher`) are rejected immediately.

2. **Length Constraints**:
   - Target URL capped at 2,048 characters to prevent buffer exhaustion and URL-based DoS attacks.

3. **DNS Pre-Resolution & IP Range Enforcement**:
   - Hostnames are resolved to `InetAddress` prior to persisting the link.
   - Resolved IP addresses are matched against prohibited CIDR ranges:
     - **IPv4 Loopback**: `127.0.0.0/8`
     - **IPv6 Loopback**: `::1`, `0:0:0:0:0:0:0:1`
     - **RFC-1918 Private Subnets**: `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`
     - **Cloud Metadata Endpoints**: `169.254.169.254` (AWS, GCP, Azure Instance Metadata Services)
     - **Carrier-Grade NAT & Link Local**: `100.64.0.0/10`, `169.254.0.0/16`
     - **Broadcast / Anycast**: `0.0.0.0/8`, `255.255.255.255`

4. **Self-Referencing Loop Detection**:
   - The validator compares target host, port, and prefix against the configured application base URL (`app.base-url`). Target URLs pointing back to the shortener's own redirect domain are rejected with HTTP 400 Bad Request.

5. **Reserved Word Protection**:
   - Custom aliases are checked against system reserved keywords (`api`, `actuator`, `health`, `metrics`, `admin`, `login`, `swagger`, `docs`, `static`) to prevent shadowing internal endpoints.

## Consequences
- **Positive**: Complete defense against cloud instance metadata exfiltration, internal network reconnaissance, and redirect loops.
- **Trade-off**: Requires DNS resolution during link shortening, adding ~5–20ms to the link creation path (which is not latency-critical, unlike the redirect fast path).
