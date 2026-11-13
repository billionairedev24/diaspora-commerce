package com.afrotransact.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Which operational endpoints may be public (security audit F3). Health probes always are. {@code /actuator/prometheus}
 * is public only on a separate management port ({@code management.server.port}, set by the Helm chart in every cluster
 * environment) that no Gateway route targets. On the public listener it needs a token, so it is never exposed through a
 * prefixed public path such as {@code /identity/actuator/prometheus}.
 */
public final class ManagementEndpoints {

    /** Paths any caller may read on the public listener. Prometheus is deliberately absent. */
    public static final String[] ALWAYS_PUBLIC_OPERATIONAL = {"/actuator/health/**", "/actuator/info"};

    static final String PROMETHEUS_PATH = "/actuator/prometheus";

    private ManagementEndpoints() {
    }

    /** Matches the Prometheus endpoint only when the request arrived on the dedicated management port. */
    public static RequestMatcher prometheusOnManagementPort(Environment env) {
        Integer management = env.getProperty("management.server.port", Integer.class);
        int serverPort = env.getProperty("server.port", Integer.class, 8080);
        boolean separate = management != null && management > 0 && management != serverPort;
        return (HttpServletRequest request) -> separate && request.getLocalPort() == management
                && PROMETHEUS_PATH.equals(request.getRequestURI());
    }
}
