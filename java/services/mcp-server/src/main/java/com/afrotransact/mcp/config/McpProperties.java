package com.afrotransact.mcp.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code at.mcp.*}.
 *
 * @param publicUrl       external base URL of this server (resource indicator and discovery metadata)
 * @param scopesSupported scopes advertised to MCP clients
 * @param allowedOrigins  browser origins allowed to call /mcp (DNS-rebinding protection); agents without an Origin
 *                        header are not affected
 * @param rateLimit       per client + subject token bucket
 */
@ConfigurationProperties(prefix = "at.mcp")
public record McpProperties(String publicUrl, List<String> scopesSupported, List<String> allowedOrigins,
                            RateLimit rateLimit) {

    public McpProperties {
        publicUrl = publicUrl == null ? "http://localhost:8114" : publicUrl;
        scopesSupported = scopesSupported == null ? List.of("mcp:shopper", "mcp:seller", "mcp:staff")
                : List.copyOf(scopesSupported);
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        rateLimit = rateLimit == null ? new RateLimit(null, null) : rateLimit;
    }

    /** Token bucket: {@code capacity} calls of burst, refilled at {@code refillPerMinute}. */
    public record RateLimit(Long capacity, Long refillPerMinute) {

        public RateLimit {
            capacity = capacity == null ? 60 : capacity;
            refillPerMinute = refillPerMinute == null ? 60 : refillPerMinute;
        }
    }
}
