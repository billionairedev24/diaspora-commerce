package com.afrotransact.identity.config;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

/**
 * The security response headers of every identity filter chain, in one place. Spring Security's defaults stay on
 * ({@code X-Content-Type-Options: nosniff}, {@code Cache-Control: no-store} for dynamic responses,
 * {@code X-Frame-Options: DENY}); this adds the Content-Security-Policy, HSTS (written by Spring Security only for
 * secure requests, which includes requests the proxy forwarded as https), Referrer-Policy and Permissions-Policy.
 */
public final class IdentitySecurityHeaders {

    static final long HSTS_SECONDS = 31_536_000L;

    /** Passkeys (WebAuthn) run on the hosted pages; every other powerful feature is off. */
    static final String PERMISSIONS_POLICY = "publickey-credentials-get=(self), publickey-credentials-create=(self), "
            + "camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()";

    private IdentitySecurityHeaders() {
    }

    public static void apply(HttpSecurity http, HeaderWriter contentSecurityPolicy) {
        http.headers(headers -> headers
                .addHeaderWriter(contentSecurityPolicy)
                .httpStrictTransportSecurity(h -> h.maxAgeInSeconds(HSTS_SECONDS).includeSubDomains(true))
                .referrerPolicy(r -> r.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", PERMISSIONS_POLICY)));
    }
}
