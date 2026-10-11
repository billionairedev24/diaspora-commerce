package com.afrotransact.platform.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code at.security.*}.
 *
 * @param enabled     false only in the local profile; every request then runs as a local super admin
 * @param publicPaths     extra unauthenticated GET paths (e.g. public catalogue reads)
 * @param publicPostPaths unauthenticated POST paths that authenticate another way (signed webhooks, sign-ups)
 * @param corsAllowedOrigins browser origins allowed to call this service directly (CORS); empty = none. Locally the
 *                           Expo web previews of the mobile apps (make up sets AT_SECURITY_CORS_ALLOWED_ORIGINS).
 */
@ConfigurationProperties(prefix = "at.security")
public record PlatformSecurityProperties(Boolean enabled, List<String> publicPaths, List<String> publicPostPaths,
        List<String> corsAllowedOrigins) {

    public PlatformSecurityProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        publicPaths = publicPaths == null ? List.of() : List.copyOf(publicPaths);
        publicPostPaths = publicPostPaths == null ? List.of() : List.copyOf(publicPostPaths);
        corsAllowedOrigins = corsAllowedOrigins == null ? List.of() : List.copyOf(corsAllowedOrigins);
    }
}
