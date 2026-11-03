package com.afrotransact.seller.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.afrotransact.platform.security.PlatformSecurityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * Same stateless resource-server rules as platform-security, plus unauthenticated POSTs for Stripe webhooks (they
 * authenticate with their signature) and the launch list. Replaces the platform filter chain for this service.
 */
@Configuration(proxyBeanMethods = false)
class SellerSecurityConfiguration {

    private static final String[] ALWAYS_PUBLIC = {
        "/actuator/health/**", "/actuator/info", "/actuator/prometheus",
        "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/docs", "/redoc", "/error"
    };
    private static final String[] PUBLIC_POSTS = {"/v1/webhooks/stripe-connect", "/v1/webhooks/stripe-billing",
        "/v1/launch-list"};

    @Bean
    SecurityFilterChain sellerSecurityFilterChain(HttpSecurity http, PlatformSecurityProperties props)
            throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults());
        if (!props.enabled()) {
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
            return http.build();
        }
        String[] publicGets = props.publicPaths().toArray(String[]::new);
        http.authorizeHttpRequests(a -> {
            a.requestMatchers(ALWAYS_PUBLIC).permitAll();
            a.requestMatchers(HttpMethod.POST, PUBLIC_POSTS).permitAll();
            a.requestMatchers(HttpMethod.GET, "/v1/public/business-pages/*").permitAll();
            if (publicGets.length > 0) {
                a.requestMatchers(HttpMethod.GET, publicGets).permitAll();
            }
            a.anyRequest().authenticated();
        }).oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter())));
        return http.build();
    }

    private static JwtAuthenticationConverter jwtConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SellerSecurityConfiguration::authorities);
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
