package com.afrotransact.platform.web.error;

import java.net.URI;

/**
 * RFC 9457 problem {@code type} URIs. A type is an identifier, never fetched by clients, so one stable base serves
 * every environment (local, dev, uat and prod alike); the developer portal documents each code under it. The Go
 * services use the same base ({@code go/pkg/problem.TypeBase}).
 */
public final class ProblemTypes {

    /** Base of every problem type: {@code BASE + code}, for example {@code .../problems/catalog.invalid-input}. */
    public static final String BASE = "https://developers.afrotransact.com/problems/";

    private ProblemTypes() {
    }

    /**
     * The problem type URI for a stable error code.
     *
     * @param code the dotted, kebab-case code, for example {@code identity.invalid-input}
     * @return {@code BASE + code}
     */
    public static URI of(String code) {
        return URI.create(BASE + code);
    }
}
