package com.afrotransact.content.config;

import com.afrotransact.platform.security.PlatformSecurityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The platform resource-server chain plus anonymous POSTs for visitor forms and flag evaluation
 * ({@code at.content.public-post-paths}). A bearer token, when present, is still validated so authenticated
 * callers keep their identity (flag role targeting uses it).
 */
@Configuration(proxyBeanMethods = false)
class ContentSecurityConfiguration {

    private static final String[] ALWAYS_PUBLIC = {
        "/actuator/health/**", "/actuator/info", "/actuator/prometheus",
        "/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/docs", "/redoc",
        "/error"
    };

    @Bean
    SecurityFilterChain contentSecurityFilterChain(HttpSecurity http, PlatformSecurityProperties security,
                                                   ContentProperties content) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults());
        if (!security.enabled()) {
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
            return http.build();
        }
        String[] publicGets = security.publicPaths().toArray(String[]::new);
        String[] publicPosts = content.publicPostPaths().toArray(String[]::new);
        http.authorizeHttpRequests(a -> {
            a.requestMatchers(ALWAYS_PUBLIC).permitAll();
            if (publicGets.length > 0) {
                a.requestMatchers(HttpMethod.GET, publicGets).permitAll();
            }
            if (publicPosts.length > 0) {
                a.requestMatchers(HttpMethod.POST, publicPosts).permitAll();
            }
            a.anyRequest().authenticated();
        }).oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()));
        return http.build();
    }
}
