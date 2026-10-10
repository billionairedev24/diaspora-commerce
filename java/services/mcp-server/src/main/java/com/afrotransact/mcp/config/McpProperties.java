package com.afrotransact.mcp.config;

import java.time.Duration;
import java.util.List;

import com.afrotransact.mcp.domain.RateLimitFailureMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code at.mcp.*}.
 *
 * @param publicUrl       external base URL of this server (resource indicator and discovery metadata)
 * @param scopesSupported scopes advertised to MCP clients
 * @param allowedOrigins  browser origins allowed to call /mcp (DNS-rebinding protection); agents without an Origin
 *                        header are not affected
 * @param rateLimit       per client + subject token bucket
 * @param audit           durable audit path (Valkey spool and replay)
 */
@ConfigurationProperties(prefix = "at.mcp")
public record McpProperties(String publicUrl, List<String> scopesSupported, List<String> allowedOrigins,
                            RateLimit rateLimit, Audit audit) {

    public McpProperties {
        publicUrl = publicUrl == null ? "http://localhost:8114" : publicUrl;
        scopesSupported = scopesSupported == null ? List.of("mcp:shopper", "mcp:seller", "mcp:staff")
                : List.copyOf(scopesSupported);
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        rateLimit = rateLimit == null ? new RateLimit(null, null, null, null, null) : rateLimit;
        audit = audit == null ? new Audit(null, null, null, null, null, null) : audit;
    }

    /**
     * Token bucket: {@code capacity} calls of burst, refilled at {@code refillPerMinute}.
     *
     * @param capacity        burst
     * @param refillPerMinute sustained rate
     * @param store           {@code valkey} (default: one global bucket shared by all replicas) or {@code memory}
     *                        (per pod; tests and single-instance tools only)
     * @param failureMode     when Valkey is unreachable: {@code open} (default; per-pod in-memory bucket) or
     *                        {@code closed} (refuse calls)
     * @param keyPrefix       Valkey key prefix of the buckets
     */
    public record RateLimit(Long capacity, Long refillPerMinute, Store store, RateLimitFailureMode failureMode,
                            String keyPrefix) {

        public RateLimit {
            capacity = capacity == null ? 60 : capacity;
            refillPerMinute = refillPerMinute == null ? 60 : refillPerMinute;
            store = store == null ? Store.VALKEY : store;
            failureMode = failureMode == null ? RateLimitFailureMode.OPEN : failureMode;
            keyPrefix = keyPrefix == null ? "mcp:rl:" : keyPrefix;
        }
    }

    /** Where rate-limit buckets live. */
    public enum Store {
        VALKEY,
        MEMORY
    }

    /**
     * Durable audit: events Kafka refuses go to a Valkey stream and are replayed in order.
     *
     * @param spoolStream    stream key
     * @param spoolMaxLength approximate cap of the stream (oldest entries are trimmed beyond it)
     * @param replayInterval delay between replay runs
     * @param replayBatch    entries read per replay round trip
     * @param publishTimeout how long replay waits for Kafka to acknowledge one event
     * @param replayLease    how long one replica holds the replay lease
     */
    public record Audit(String spoolStream, Long spoolMaxLength, Duration replayInterval, Integer replayBatch,
                        Duration publishTimeout, Duration replayLease) {

        public Audit {
            spoolStream = spoolStream == null ? "mcp:audit:spool" : spoolStream;
            spoolMaxLength = spoolMaxLength == null ? 1_000_000L : spoolMaxLength;
            replayInterval = replayInterval == null ? Duration.ofSeconds(5) : replayInterval;
            replayBatch = replayBatch == null ? 200 : replayBatch;
            publishTimeout = publishTimeout == null ? Duration.ofSeconds(10) : publishTimeout;
            replayLease = replayLease == null ? Duration.ofSeconds(60) : replayLease;
        }
    }
}
