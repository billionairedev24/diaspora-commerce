package com.afrotransact.mcp.config;

import java.util.List;

import com.afrotransact.platform.security.PlatformSecurityProperties;
import com.afrotransact.platform.web.error.ProblemTypes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Resource server for MCP clients (OAuth 2.1 bearer tokens from the identity service). Same rules as the platform
 * default chain, plus the MCP authorization spec: a 401 carries {@code WWW-Authenticate: Bearer resource_metadata=…}
 * pointing at the RFC 9728 protected resource metadata (resource, identity issuer, supported scopes), and that
 * metadata is public. MCP clients then run Authorization Code + PKCE against the identity service.
 */
@Configuration(proxyBeanMethods = false)
class McpSecurityConfiguration {

    private static final String[] PUBLIC = {
        "/actuator/health/**", "/actuator/info", "/actuator/prometheus",
        "/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/docs",
        "/redoc", "/error", "/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/**"
    };

    @Bean
    SecurityFilterChain mcpSecurityFilterChain(HttpSecurity http, PlatformSecurityProperties security,
                                               McpProperties props,
                                               @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
                                               String issuer) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults());
        if (!security.enabled()) {
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
            return http.build();
        }
        AuthenticationEntryPoint entryPoint = resourceMetadataEntryPoint(props.publicUrl());
        String[] extra = security.publicPaths().toArray(String[]::new);
        http.authorizeHttpRequests(a -> {
            a.requestMatchers(PUBLIC).permitAll();
            if (extra.length > 0) {
                a.requestMatchers(HttpMethod.GET, extra).permitAll();
            }
            a.anyRequest().authenticated();
        })
                .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(entryPoint)
                        // RFC 9728 document served by Spring Security at /.well-known/oauth-protected-resource
                        .protectedResourceMetadata(m -> m.protectedResourceMetadataCustomizer(b -> b
                                .resource(props.publicUrl().replaceAll("/+$", "") + "/mcp")
                                .authorizationServers(list -> replace(list, List.of(issuer)))
                                .scopes(list -> replace(list, props.scopesSupported()))
                                .bearerMethods(list -> replace(list, List.of("header")))
                                .resourceName("AfroTransact MCP server"))))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint));
        return http.build();
    }

    private static void replace(List<String> target, List<String> values) {
        target.clear();
        target.addAll(values);
    }

    static AuthenticationEntryPoint resourceMetadataEntryPoint(String publicUrl) {
        String metadata = publicUrl.replaceAll("/+$", "") + "/.well-known/oauth-protected-resource";
        return (request, response, ex) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer resource_metadata=\"" + metadata + "\""
                    + (request.getHeader(HttpHeaders.AUTHORIZATION) == null ? "" : ", error=\"invalid_token\""));
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("{\"type\":\"" + ProblemTypes.BASE + "auth.unauthenticated\","
                    + "\"title\":\"Unauthorized\",\"status\":401,\"code\":\"auth.unauthenticated\","
                    + "\"detail\":\"A bearer token from the AfroTransact identity service is required\"}");
        };
    }
}
