package com.afrotransact.platform.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code at.security.*}.
 *
 * @param enabled     false only in the local profile; every request then runs as a local super admin
 * @param publicPaths extra unauthenticated paths (e.g. public catalogue reads)
 */
@ConfigurationProperties(prefix = "at.security")
public record PlatformSecurityProperties(Boolean enabled, List<String> publicPaths) {

    public PlatformSecurityProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        publicPaths = publicPaths == null ? List.of() : List.copyOf(publicPaths);
    }
}
