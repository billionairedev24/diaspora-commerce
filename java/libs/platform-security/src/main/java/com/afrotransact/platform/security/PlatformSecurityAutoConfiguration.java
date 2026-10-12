package com.afrotransact.platform.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** Stateless resource-server defaults. API docs, health and metrics stay public; everything else needs a JWT. */
@AutoConfiguration(beforeName = "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration")
@EnableConfigurationProperties(PlatformSecurityProperties.class)
public class PlatformSecurityAutoConfiguration {

    private static final String[] ALWAYS_PUBLIC = {
        "/actuator/health/**", "/actuator/info", "/actuator/prometheus",
        "/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/docs", "/redoc", "/error"
    };

    /**
     * The token's actor (local profile: {@link LocalActorResolver}), acting as a shopper when a storefront request asks
     * for it ({@link ActingAsActorResolver}).
     */
    @Bean
    @ConditionalOnMissingBean
    public ActorResolver actorResolver(PlatformSecurityProperties props) {
        return new ActingAsActorResolver(props.enabled() ? new JwtActorResolver() : new LocalActorResolver());
    }

    @Bean
    @ConditionalOnMissingBean
    public PermissionChecker permissionChecker(ActorResolver actors) {
        return new DefaultPermissionChecker(actors);
    }

    /**
     * CORS for browsers calling the service directly; no origins configured = cross-origin calls stay refused. Matched by
     * name: Spring MVC's HandlerMappingIntrospector is also a CorsConfigurationSource, and Spring Security's
     * {@code cors()} looks the source up as the bean named corsConfigurationSource.
     */
    @Bean
    @ConditionalOnMissingBean(name = "corsConfigurationSource")
    public CorsConfigurationSource corsConfigurationSource(PlatformSecurityProperties props) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(props.corsAllowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("*"));
        cors.setExposedHeaders(List.of("Location", "ETag", "Retry-After"));
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    @Bean
    @ConditionalOnMissingBean
    public SecurityFilterChain platformSecurityFilterChain(HttpSecurity http, PlatformSecurityProperties props)
            throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults());
        if (!props.enabled()) {
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
            return http.build();
        }
        String[] extra = props.publicPaths().toArray(String[]::new);
        String[] posts = props.publicPostPaths().toArray(String[]::new);
        http.authorizeHttpRequests(a -> {
            a.requestMatchers(ALWAYS_PUBLIC).permitAll();
            if (extra.length > 0) {
                a.requestMatchers(HttpMethod.GET, extra).permitAll();
            }
            if (posts.length > 0) {
                a.requestMatchers(HttpMethod.POST, posts).permitAll();
            }
            a.anyRequest().authenticated();
        })
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter())));
        return http.build();
    }

    private static JwtAuthenticationConverter jwtConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(PlatformSecurityAutoConfiguration::authorities);
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
