package com.afrotransact.mcp.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.afrotransact.platform.security.PlatformSecurityProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The MCP server is reachable from the internet outside the local profile. It refuses to start there with a
 * configuration that would make a tool callable without a verified token: security switched off, an issuer that is not
 * the identity service over https, or a public URL that is not https. Only the local profile may run unauthenticated.
 */
@Component
@Profile("!local")
class McpExposureGuard {

    McpExposureGuard(PlatformSecurityProperties security, McpProperties props,
                     @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
        List<String> problems = problems(security.enabled(), props.publicUrl(), issuer);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("MCP server is publicly reachable and refuses to start: "
                    + String.join("; ", problems));
        }
    }

    static List<String> problems(boolean securityEnabled, String publicUrl, String issuer) {
        List<String> problems = new ArrayList<>();
        if (!securityEnabled) {
            problems.add("at.security.enabled must be true (AT_SECURITY_ENABLED=false makes every tool anonymous)");
        }
        if (!isHttps(publicUrl)) {
            problems.add("MCP_PUBLIC_URL must be the https address MCP clients use, got '" + publicUrl + "'");
        }
        if (!isHttps(issuer)) {
            problems.add("AUTH_ISSUER_URL must be the https issuer of the identity service, got '" + issuer + "'");
        }
        return problems;
    }

    private static boolean isHttps(String url) {
        if (url == null) {
            return false;
        }
        String u = url.trim().toLowerCase(Locale.ROOT);
        return u.startsWith("https://") && !u.contains("localhost") && !u.contains("127.0.0.1");
    }
}
