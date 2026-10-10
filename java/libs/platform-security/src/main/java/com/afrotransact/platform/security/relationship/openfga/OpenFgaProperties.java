package com.afrotransact.platform.security.relationship.openfga;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code at.security.fga.*}. Nothing is created unless {@code enabled=true}.
 *
 * @param enabled         turn relationship checks on (default false)
 * @param enforce         false = shadow mode: denials and store errors are logged, the call is allowed (default true)
 * @param apiUrl          OpenFGA HTTP API, e.g. {@code http://openfga.authz:8080} (local: {@code http://localhost:8091})
 * @param storeId         store id; when blank the store is looked up once by {@code storeName}
 * @param storeName       store name (default {@code afrotransact})
 * @param modelId         authorization model id; blank = the store's latest model
 * @param apiToken        pre-shared key ({@code Authorization: Bearer}); blank when OpenFGA runs without authn
 * @param timeout         connect and read timeout per call (default 500 ms)
 * @param cacheTtl        how long a check result is reused by this instance (default 5 s; 0 disables the cache)
 * @param cacheMaxEntries upper bound of cached check results (default 10 000)
 */
@ConfigurationProperties(prefix = "at.security.fga")
public record OpenFgaProperties(Boolean enabled, Boolean enforce, URI apiUrl, String storeId, String storeName,
                                String modelId, String apiToken, Duration timeout, Duration cacheTtl,
                                Integer cacheMaxEntries) {

    public OpenFgaProperties {
        enabled = enabled != null && enabled;
        enforce = enforce == null || enforce;
        apiUrl = apiUrl == null ? URI.create("http://localhost:8091") : apiUrl;
        storeId = blankToNull(storeId);
        storeName = storeName == null || storeName.isBlank() ? "afrotransact" : storeName.trim();
        modelId = blankToNull(modelId);
        apiToken = blankToNull(apiToken);
        timeout = timeout == null ? Duration.ofMillis(500) : timeout;
        cacheTtl = cacheTtl == null ? Duration.ofSeconds(5) : cacheTtl;
        cacheMaxEntries = cacheMaxEntries == null ? 10_000 : cacheMaxEntries;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
