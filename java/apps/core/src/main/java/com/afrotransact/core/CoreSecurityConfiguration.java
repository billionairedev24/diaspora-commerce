package com.afrotransact.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.afrotransact.module.SecurityPermits;
import com.afrotransact.platform.security.ManagementEndpoints;
import com.afrotransact.platform.security.PlatformSecurityProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The one resource-server chain of the core application: the platform defaults (health, API docs, the union of the modules'
 * {@code at.security.public-paths}) plus every module's {@link SecurityPermits}, so a module's public storefront reads and signed
 * webhooks stay anonymous when modules share a JVM. A module's own chain bean is switched off in core (it would match every
 * request and shadow the others). Everything else needs a JWT, exactly as in each standalone service.
 */
@Configuration(proxyBeanMethods = false)
class CoreSecurityConfiguration {

    private static final String[] ALWAYS_PUBLIC = {
        "/actuator/health/**", "/actuator/info",
        "/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/docs", "/redoc", "/error"
    };

    @Bean
    SecurityFilterChain coreSecurityFilterChain(HttpSecurity http, PlatformSecurityProperties props, Environment env,
                                                ObjectProvider<SecurityPermits> permits) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults());
        if (!props.enabled()) {
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
            return http.build();
        }
        Set<String> gets = new LinkedHashSet<>(props.publicPaths());
        Set<String> posts = new LinkedHashSet<>(props.publicPostPaths());
        permits.orderedStream().forEach(p -> {
            gets.addAll(p.publicGets());
            posts.addAll(p.publicPosts());
        });
        http.authorizeHttpRequests(a -> {
            a.requestMatchers(ALWAYS_PUBLIC).permitAll();
            a.requestMatchers(ManagementEndpoints.prometheusOnManagementPort(env)).permitAll();
            if (!gets.isEmpty()) {
                a.requestMatchers(HttpMethod.GET, gets.toArray(String[]::new)).permitAll();
            }
            if (!posts.isEmpty()) {
                a.requestMatchers(HttpMethod.POST, posts.toArray(String[]::new)).permitAll();
            }
            a.anyRequest().authenticated();
        }).oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter())));
        return http.build();
    }

    private static JwtAuthenticationConverter jwtConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(CoreSecurityConfiguration::authorities);
        return converter;
    }

    private static Collection<GrantedAuthority> authorities(Jwt jwt) {
        List<GrantedAuthority> out = new ArrayList<>();
        Object perms = jwt.getClaim("perms");
        if (perms instanceof Collection<?> values) {
            values.forEach(v -> out.add(new SimpleGrantedAuthority("PERM_" + v)));
        }
        String scope = jwt.getClaimAsString("scope");
        if (scope != null && !scope.isBlank()) {
            for (String s : scope.split(" ")) {
                out.add(new SimpleGrantedAuthority("SCOPE_" + s));
            }
        }
        return out;
    }
}
