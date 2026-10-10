package com.afrotransact.offers.adapter.in.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/**
 * Optimistic locking over HTTP (S259): offer responses carry {@code ETag: "<version>"}; writes may send
 * {@code If-Match} with it, and a newer stored version answers 409 {@code offers.version-mismatch}.
 */
final class Versions {

    static final String IF_MATCH = HttpHeaders.IF_MATCH;

    private Versions() {
    }

    /** Parses {@code "3"}, {@code W/"3"} or {@code 3}; null, blank or {@code *} means "any version". */
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
            throw new IllegalArgumentException("If-Match must be the offer version from the ETag, e.g. \"3\"", e);
        }
    }

    /** The header wins over the body; both must agree when both are sent. */
    static Long expected(String ifMatch, Long bodyVersion) {
        Long header = expected(ifMatch);
        if (header != null && bodyVersion != null && !header.equals(bodyVersion)) {
            throw new IllegalArgumentException("If-Match " + header + " and version " + bodyVersion + " differ");
        }
        return header != null ? header : bodyVersion;
    }

    static <T> ResponseEntity<T> tagged(T body, long version) {
        return ResponseEntity.ok().eTag("\"" + version + "\"").body(body);
    }
}
