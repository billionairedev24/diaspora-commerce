package com.afrotransact.mcp.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.afrotransact.platform.security.PlatformSecurityProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The MCP server is private (cluster-internal, no public hostname) but still refuses to start outside the local profile
 * with a configuration that would make a tool callable without a verified token: security switched off, or an issuer that
 * is not the identity service over https. Its own URL (the resource indicator) is either an https address or a
 * cluster-internal one (a Kubernetes service name, {@code *.svc} or {@code *.svc.cluster.local}); the internal form is
 * acceptable because security must be on, which is checked first. Only the local profile may run unauthenticated.
 */
@Component
@Profile("!local")
class McpExposureGuard {

    McpExposureGuard(PlatformSecurityProperties security, McpProperties props,
                     @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
        List<String> problems = problems(security.enabled(), props.publicUrl(), issuer);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("MCP server refuses to start with an unsafe configuration: "
                    + String.join("; ", problems));
        }
    }

    static List<String> problems(boolean securityEnabled, String publicUrl, String issuer) {
        List<String> problems = new ArrayList<>();
        if (!securityEnabled) {
            problems.add("at.security.enabled must be true (AT_SECURITY_ENABLED=false makes every tool anonymous)");
        }
        if (!isHttps(publicUrl) && !isClusterInternal(publicUrl)) {
            problems.add("MCP_PUBLIC_URL must be an https address or the in-cluster service URL "
                    + "(http://mcp-server.<namespace>.svc.cluster.local), got '" + publicUrl + "'");
        }
        if (!isHttps(issuer)) {
            problems.add("AUTH_ISSUER_URL must be the https issuer of the identity service, got '" + issuer + "'");
        }
        return problems;
    }

    /** An http(s) URL whose host is a bare service name or ends in .svc / .svc.cluster.local; never localhost or an IP. */
    private static boolean isClusterInternal(String url) {
        if (url == null) {
            return false;
        }
        String u = url.trim().toLowerCase(Locale.ROOT);
        int scheme = u.indexOf("://");
        if (scheme < 0 || !(u.startsWith("http://") || u.startsWith("https://"))) {
            return false;
        }
        String host = u.substring(scheme + 3).split("[/:?#]", 2)[0];
        if (host.isEmpty() || host.equals("localhost") || host.matches("[0-9.]+") || host.contains("@")) {
            return false;
        }
        return host.endsWith(".svc") || host.endsWith(".svc.cluster.local") || !host.contains(".");
    }

    private static boolean isHttps(String url) {
        if (url == null) {
            return false;
        }
        String u = url.trim().toLowerCase(Locale.ROOT);
        return u.startsWith("https://") && !u.contains("localhost") && !u.contains("127.0.0.1");
    }
}
