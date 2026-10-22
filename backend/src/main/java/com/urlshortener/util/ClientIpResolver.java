package com.urlshortener.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Security-hardened Client IP Resolver.
 * 
 * Prevents IP spoofing vulnerabilities by validating upstream proxy trust:
 * 1. If remoteAddr matches a trusted reverse proxy (e.g. Nginx on loopback or Docker bridge subnet),
 *    the X-Forwarded-For client IP is trusted and extracted.
 * 2. If remoteAddr is an untrusted direct connection (e.g. direct internet client),
 *    any client-supplied X-Forwarded-For header is strictly ignored, preventing bot spoofing.
 */
@Component
public class ClientIpResolver {

    private final Set<String> explicitTrustedProxies = ConcurrentHashMap.newKeySet();

    public ClientIpResolver(@Value("${app.security.trusted-proxies:127.0.0.1,::1,0:0:0:0:0:0:0:1}") String proxies) {
        if (proxies != null && !proxies.isBlank()) {
            for (String p : proxies.split(",")) {
                explicitTrustedProxies.add(p.trim());
            }
        }
    }

    public boolean isTrustedProxy(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return false;
        }
        String clean = remoteAddr.trim();
        if (explicitTrustedProxies.contains(clean)) {
            return true;
        }
        // Strict loopback check only (127.0.0.1, ::1)
        return clean.equals("127.0.0.1") || clean.equals("::1") || clean.equals("0:0:0:0:0:0:0:1");
    }

    public String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return "127.0.0.1";
        }
        String remoteAddr = request.getRemoteAddr();
        if (remoteAddr != null) {
            remoteAddr = remoteAddr.trim();
        }

        // If the immediate upstream connection is not a trusted proxy,
        // strictly ignore XFF to prevent external spoofing attacks.
        if (!isTrustedProxy(remoteAddr)) {
            return (remoteAddr != null && !remoteAddr.isBlank()) ? remoteAddr : "127.0.0.1";
        }

        // Upstream is a trusted proxy: parse X-Forwarded-For right-to-left
        String xff = request.getHeader("X-Forwarded-For");
        if (xff == null || xff.isBlank() || "unknown".equalsIgnoreCase(xff.trim())) {
            return (remoteAddr != null && !remoteAddr.isBlank()) ? remoteAddr : "127.0.0.1";
        }

        String[] hops = xff.split(",");
        // Walk right-to-left: find the first untrusted hop
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (!hop.isEmpty() && !isTrustedProxy(hop)) {
                return hop;
            }
        }

        // Fallback to leftmost if all hops in chain were trusted
        String firstHop = hops[0].trim();
        return firstHop.isEmpty() ? remoteAddr : firstHop;
    }
}
