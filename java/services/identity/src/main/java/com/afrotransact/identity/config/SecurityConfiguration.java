package com.afrotransact.identity.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.afrotransact.identity.adapter.in.security.AccountUserDetailsService;
import com.afrotransact.identity.adapter.in.security.EnabledProviderAuthorizationRequestResolver;
import com.afrotransact.identity.adapter.in.security.FederatedOAuth2UserService;
import com.afrotransact.identity.adapter.in.security.FederatedOidcUserService;
import com.afrotransact.identity.adapter.in.security.HostedLoginProtection;
import com.afrotransact.identity.adapter.in.security.LoginTicketAuthenticationFilter;
import com.afrotransact.identity.adapter.in.security.LoginTicketAuthenticationProvider;
import com.afrotransact.identity.adapter.in.security.MfaAwareAuthenticationSuccessHandler;
import com.afrotransact.identity.adapter.in.security.MfaChallengeAccessDeniedHandler;
import com.afrotransact.identity.adapter.in.security.PublicClientRefreshTokenAuthenticationConverter;
import com.afrotransact.identity.adapter.in.security.PublicClientRefreshTokenAuthenticationProvider;
import com.afrotransact.identity.adapter.in.security.TrustedBrowserCookie;
import com.afrotransact.identity.adapter.out.passkey.SignCountVerifyingRelyingParty;
import com.afrotransact.identity.adapter.out.passkey.WebAuthnPasskeyAssertions;
import com.afrotransact.identity.application.FederatedLoginService;
import com.afrotransact.identity.application.FirstPartySignInService;
import com.afrotransact.identity.application.IdentityProviderService;
import com.afrotransact.identity.application.SignInProtectionService;
import com.afrotransact.identity.application.SignInService;
import com.afrotransact.identity.port.PasskeyAssertions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.webauthn.management.JdbcPublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.management.JdbcUserCredentialRepository;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.security.web.webauthn.management.Webauthn4JRelyingPartyOperations;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Three filter chains.
 * <ol>
 *   <li>Authorization server endpoints (authorize, token, JWKS, discovery, userinfo, revocation, logout). The
 *   authorize endpoint also accepts a first-party {@code login_ticket} from gateway-bff.</li>
 *   <li>{@code /v1/**}: stateless JSON API protected by this server's own access tokens.</li>
 *   <li>Browser sign-in: password form (brute-force protected), social login, passkeys (WebAuthn), two-step
 *   verification.</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    private static final String[] PUBLIC_BROWSER_PATHS = {
        "/", "/login", "/login/mfa", "/login/mfa/**", "/error", "/favicon.ico",
        "/actuator/health/**", "/actuator/info", "/actuator/prometheus",
        "/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/docs", "/redoc"
    };

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain authorizationServerChain(HttpSecurity http, OAuth2TokenGenerator<?> tokenGenerator,
                                                 RegisteredClientRepository clients,
                                                 SecurityContextRepository contexts, RequestCache requestCache,
                                                 FirstPartySignInService firstParty, UserDetailsService accounts,
                                                 AuthorizationServerSettings settings)
            throws Exception {
        http.oauth2AuthorizationServer(as -> {
            http.securityMatcher(as.getEndpointsMatcher());
            as.oidc(Customizer.withDefaults())
                    .tokenGenerator(tokenGenerator)
                    .clientAuthentication(c -> c
                            .authenticationConverter(new PublicClientRefreshTokenAuthenticationConverter())
                            .authenticationProviders(providers -> providers.add(0,
                                    new PublicClientRefreshTokenAuthenticationProvider(clients))));
        });
        MediaTypeRequestMatcher html = new MediaTypeRequestMatcher(MediaType.TEXT_HTML);
        html.setIgnoredMediaTypes(Set.of(MediaType.ALL));
        http.authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()))
                .securityContext(c -> c.securityContextRepository(contexts))
                .requestCache(r -> r.requestCache(requestCache))
                .cors(Customizer.withDefaults())
                .exceptionHandling(e -> e
                        .defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/login"), html)
                        .accessDeniedHandler(new MfaChallengeAccessDeniedHandler(requestCache)));
        // Before the authorization request is pre-validated (it captures the principal), after the context is loaded.
        http.addFilterAfter(new LoginTicketAuthenticationFilter(new ProviderManager(
                new LoginTicketAuthenticationProvider(firstParty, accounts)), settings.getAuthorizationEndpoint()),
                SecurityContextHolderFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/v1/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/v1/accounts", "/v1/accounts/verification",
                                "/v1/accounts/verification/resend", "/v1/invitations/lookup").permitAll()
                        .requestMatchers(HttpMethod.GET, "/v1/identity-providers").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs.jwt(j -> j.jwtAuthenticationConverter(jwtConverter())));
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain browserChain(HttpSecurity http, IdentityProperties props, SignInService signIn,
                                     TrustedBrowserCookie trustedBrowser, SecurityContextRepository contexts,
                                     RequestCache requestCache, ClientRegistrationRepository registrations,
                                     IdentityProviderService providers, FederatedLoginService federated,
                                     SignInProtectionService protection)
            throws Exception {
        HostedLoginProtection hostedProtection = new HostedLoginProtection(protection);
        MfaAwareAuthenticationSuccessHandler success = new MfaAwareAuthenticationSuccessHandler(signIn,
                hostedProtection, trustedBrowser, contexts, props.homeUrl());
        http.authorizeHttpRequests(a -> a.requestMatchers(PUBLIC_BROWSER_PATHS).permitAll()
                        .anyRequest().authenticated())
                .securityContext(c -> c.securityContextRepository(contexts))
                .requestCache(r -> r.requestCache(requestCache))
                .addFilterBefore(hostedProtection, UsernamePasswordAuthenticationFilter.class)
                .formLogin(f -> f.loginPage("/login").successHandler(success).failureHandler(hostedProtection)
                        .permitAll())
                .oauth2Login(o -> o.loginPage("/login")
                        .clientRegistrationRepository(registrations)
                        .authorizationEndpoint(e -> e.authorizationRequestResolver(
                                new EnabledProviderAuthorizationRequestResolver(registrations, providers)))
                        .userInfoEndpoint(u -> u.oidcUserService(new FederatedOidcUserService(federated))
                                .userService(new FederatedOAuth2UserService(federated)))
                        .successHandler(success)
                        .failureUrl("/login?error=social"))
                .webAuthn(w -> w.rpId(props.webauthn().rpId())
                        .rpName(props.webauthn().rpName())
                        .allowedOrigins(new HashSet<>(props.webauthn().allowedOrigins())))
                .exceptionHandling(e -> e.accessDeniedHandler(new MfaChallengeAccessDeniedHandler(requestCache)))
                .logout(l -> l.logoutSuccessUrl("/login?logout"))
                .cors(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(new RequestAttributeSecurityContextRepository(),
                new HttpSessionSecurityContextRepository());
    }

    @Bean
    RequestCache requestCache() {
        return new HttpSessionRequestCache();
    }

    @Bean
    UserDetailsService userDetailsService(SignInService signIn) {
        return new AccountUserDetailsService(signIn);
    }

    @Bean
    PublicKeyCredentialUserEntityRepository publicKeyCredentialUserEntityRepository(JdbcTemplate jdbc) {
        return new JdbcPublicKeyCredentialUserEntityRepository(jdbc);
    }

    @Bean
    UserCredentialRepository userCredentialRepository(JdbcTemplate jdbc) {
        return new JdbcUserCredentialRepository(jdbc);
    }

    /**
     * One WebAuthn relying party for the hosted pages ({@code webAuthn()} picks this bean up) and the first-party
     * passkey API. Allowed origins include the storefront origins whose own forms run the ceremony. The signature
     * counter is enforced on top of Spring Security's relying party (see {@link SignCountVerifyingRelyingParty}).
     */
    @Bean
    WebAuthnRelyingPartyOperations webAuthnRelyingPartyOperations(PublicKeyCredentialUserEntityRepository userEntities,
                                                                   UserCredentialRepository credentials,
                                                                   IdentityProperties props) {
        return new SignCountVerifyingRelyingParty(new Webauthn4JRelyingPartyOperations(userEntities, credentials,
                PublicKeyCredentialRpEntity.builder().id(props.webauthn().rpId()).name(props.webauthn().rpName())
                        .build(), new HashSet<>(props.webauthn().allowedOrigins())), credentials);
    }

    @Bean
    PasskeyAssertions passkeyAssertions(WebAuthnRelyingPartyOperations relyingParty, IdentityProperties props) {
        return new WebAuthnPasskeyAssertions(relyingParty, props.webauthn().rpId());
    }

    /** Browser apps call the token endpoint (public PKCE clients) and the /v1 API cross-origin. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(IdentityProperties props) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (props.corsAllowedOrigins().isEmpty()) {
            return source;
        }
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(props.corsAllowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-Id", "DPoP"));
        cors.setExposedHeaders(List.of("Retry-After", "X-Correlation-Id"));
        cors.setMaxAge(3600L);
        for (String path : List.of("/v1/**", "/oauth2/token", "/oauth2/revoke", "/oauth2/jwks", "/userinfo",
                "/.well-known/**")) {
            source.registerCorsConfiguration(path, cors);
        }
        return source;
    }

    private static JwtAuthenticationConverter jwtConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfiguration::authorities);
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
