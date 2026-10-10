package com.afrotransact.deliveryruns.adapter.in.web;

import com.afrotransact.deliveryruns.domain.RuleViolation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Optimistic locking over HTTP: pre-order and settings responses carry {@code ETag: "<version>"}; writes may send
 * {@code If-Match} with it, and a newer stored version answers 409 {@code delivery-runs.version-mismatch}.
 */
final class Versions {

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
            throw new RuleViolation("delivery-runs.invalid-if-match", "If-Match must be the ETag, e.g. \"3\"");
        }
    }

    static <T> ResponseEntity<T> tagged(T body, long version) {
        return ResponseEntity.ok().eTag("\"" + version + "\"").body(body);
    }

    static <T> ResponseEntity<T> created(T body, long version) {
        return ResponseEntity.status(HttpStatus.CREATED).eTag("\"" + version + "\"").body(body);
    }
}
