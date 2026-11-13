package com.afrotransact.platform.security;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;

/**
 * Fail-closed guard: {@code at.security.enabled=false} turns every caller into a staff super admin, so it is allowed only
 * in the local, test and it profiles. Any other active profile or APP_ENV (uat, prod, dev, ...) refuses to start.
 */
final class SecurityEnabledGuard implements InitializingBean {

    private static final Set<String> SECURITY_OFF_ALLOWED = Set.of("local", "test", "it");

    private final boolean enabled;
    private final String[] profiles;
    private final String appEnv;

    SecurityEnabledGuard(PlatformSecurityProperties props, Environment env) {
        this(props.enabled(), env.getActiveProfiles(), env.getProperty("APP_ENV"));
    }

    SecurityEnabledGuard(boolean enabled, String[] profiles, String appEnv) {
        this.enabled = enabled;
        this.profiles = profiles == null ? new String[0] : profiles;
        this.appEnv = appEnv;
    }

    @Override
    public void afterPropertiesSet() {
        if (enabled) {
            return;
        }
        String offending = Stream.concat(Stream.of(profiles), Stream.ofNullable(appEnv))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty() && !SECURITY_OFF_ALLOWED.contains(s))
                .findFirst()
                .orElse(null);
        if (offending != null) {
            throw new IllegalStateException("Refusing to start: at.security.enabled=false (AT_SECURITY_ENABLED=false) is allowed only"
                    + " in the local, test and it profiles, but the active profile or APP_ENV is '" + offending
                    + "'. Security off makes every caller a staff super admin. Remove AT_SECURITY_ENABLED from this environment.");
        }
    }
}
