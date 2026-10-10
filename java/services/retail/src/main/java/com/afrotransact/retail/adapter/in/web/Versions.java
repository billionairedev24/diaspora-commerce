package com.afrotransact.retail.adapter.in.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/** Optimistic locking over HTTP: responses carry {@code ETag: "<version>"}, writes may send {@code If-Match}. */
final class Versions {

    static final String IF_MATCH = HttpHeaders.IF_MATCH;
    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private Versions() {
    }

    /** Parses {@code "3"}, {@code W/"3"} or {@code 3}; null or {@code *} means "any version". */
    static Long expected(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank() || "*".equals(ifMatch.trim())) {
            return null;
        }
        String v = ifMatch.trim();
        if (v.startsWith("W/")) {
            v = v.substring(2);
        }
        v = v.replace("\"", "");
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("If-Match must be the resource version, e.g. \"3\"");
        }
    }

    static <T> ResponseEntity<T> tagged(T body, long version) {
        return ResponseEntity.ok().eTag("\"" + version + "\"").body(body);
    }
}
