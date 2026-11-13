package com.afrotransact.platform.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code at.client-ip.*}: how a service learns the visitor address behind the edge.
 *
 * @param header         the ONE request header the trusted edge sets to the visitor IP (Cloudflare:
 *                       {@code CF-Connecting-IP}); blank = {@code getRemoteAddr()} stays the socket peer. X-Forwarded-For
 *                       is never used: its first entry is client-controlled.
 * @param trustedProxies CIDRs of the peers allowed to supply that header (the in-cluster Envoy gateway); empty = the
 *                       private and loopback ranges. A request from any other peer keeps its socket address.
 */
@ConfigurationProperties(prefix = "at.client-ip")
public record ClientIpProperties(String header, List<String> trustedProxies) {

    public static final List<String> PRIVATE_RANGES = List.of("127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12",
            "192.168.0.0/16", "fc00::/7");

    public ClientIpProperties {
        header = header == null ? "" : header.trim();
        trustedProxies = trustedProxies == null || trustedProxies.isEmpty() ? PRIVATE_RANGES : List.copyOf(trustedProxies);
    }
}
