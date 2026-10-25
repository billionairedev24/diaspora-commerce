package com.afrotransact.identity.config;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.afrotransact.identity.adapter.in.security.IdentityTokenCustomizer;
import com.afrotransact.identity.adapter.in.security.PublicClientAwareRefreshTokenGenerator;
import com.afrotransact.identity.adapter.out.crypto.NimbusJwtInspector;
import com.afrotransact.identity.adapter.out.crypto.PublishedKeys;
import com.afrotransact.identity.adapter.out.crypto.StoredJwkSource;
import com.afrotransact.identity.adapter.out.persistence.GovernedRegisteredClientRepository;
import com.afrotransact.identity.application.AuthSettingsService;
import com.afrotransact.identity.application.TokenClaimsService;
import com.afrotransact.identity.port.DiscoveryReader;
import com.afrotransact.identity.port.JwtInspector;
import com.afrotransact.identity.port.SecretCipher;
import com.afrotransact.identity.port.SigningKeyStore;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.jackson.SecurityJacksonModules;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService.JsonMapperOAuth2AuthorizationParametersMapper;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService.JsonMapperOAuth2AuthorizationRowMapper;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.webauthn.jackson.WebauthnJacksonModule;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

/** Spring Authorization Server: JDBC stores on PostgreSQL, RS256 keys, token generation with platform claims. */
@Configuration(proxyBeanMethods = false)
class AuthorizationServerConfiguration {

    /** The clients as stored; the admin console and start-up provisioning read and write these. */
    @Bean
    JdbcRegisteredClientRepository storedRegisteredClientRepository(JdbcTemplate jdbc) {
        return new JdbcRegisteredClientRepository(jdbc);
    }

    /** What the authorization server sees: disabled clients hidden, token lifetimes capped, rotated secrets (S291). */
    @Bean
    @Primary
    RegisteredClientRepository registeredClientRepository(JdbcRegisteredClientRepository stored, JdbcClient jdbc,
                                                          AuthSettingsService settings, Clock clock) {
        return new GovernedRegisteredClientRepository(stored, jdbc, settings, clock);
    }

    /**
     * Authorizations store the authenticated principal as JSON. The default mapper only knows the core, web, OAuth2
     * client and authorization-server types, so an authorization started by a hosted passkey sign-in
     * ({@code WebAuthnAuthentication}) could be saved but not read back at {@code /oauth2/token} (500). The WebAuthn
     * Jackson module adds its mix-ins and allow-lists exactly those principal types (found by PasskeyCeremonyIT, S203).
     */
    @Bean
    OAuth2AuthorizationService authorizationService(JdbcTemplate jdbc, JdbcRegisteredClientRepository clients) {
        BasicPolymorphicTypeValidator.Builder allowedTypes = BasicPolymorphicTypeValidator.builder();
        WebauthnJacksonModule webauthn = new WebauthnJacksonModule();
        webauthn.configurePolymorphicTypeValidator(allowedTypes);
        JsonMapper json = JsonMapper.builder()
                .addModules(SecurityJacksonModules.getModules(getClass().getClassLoader(), allowedTypes))
                .addModule(webauthn)
                .build();
        JdbcOAuth2AuthorizationService service = new JdbcOAuth2AuthorizationService(jdbc, clients);
        service.setAuthorizationRowMapper(new JsonMapperOAuth2AuthorizationRowMapper(clients, json));
        service.setAuthorizationParametersMapper(new JsonMapperOAuth2AuthorizationParametersMapper(json));
        return service;
    }

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(JdbcTemplate jdbc,
                                                                  JdbcRegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(jdbc, clients);
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(IdentityProperties props) {
        return AuthorizationServerSettings.builder().issuer(props.issuer()).build();
    }

    /** The key from the configuration (the first signs, the rest only verify), loaded once: it seeds the stored keys. */
    @Bean
    ConfiguredSigningKeys configuredSigningKeys(IdentityProperties props) {
        return new ConfiguredSigningKeys(SigningKeys.load(props.signing()));
    }

    /** Signing and the JWKS come from the stored keys (rotatable in the staff console), see {@link StoredJwkSource}. */
    @Bean
    StoredJwkSource jwkSource(SigningKeyStore keys, SecretCipher cipher, ConfiguredSigningKeys configured,
                              Clock clock) {
        return new StoredJwkSource(keys, cipher, configured.keys(), clock);
    }

    @Bean
    JwtInspector jwtInspector(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtInspector(jwkSource);
    }

    @Bean
    DiscoveryReader discoveryReader(JWKSource<SecurityContext> jwkSource, AuthorizationServerSettings settings,
                                    IdentityProperties props) {
        Set<String> scopes = new TreeSet<>(List.of("openid", "profile", "email"));
        props.clients().forEach(c -> scopes.addAll(c.scopes()));
        return new PublishedKeys(jwkSource, settings, List.copyOf(scopes));
    }

    /** Validates tokens this server issued (used by its own /v1 API and the userinfo endpoint). */
    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource, IdentityProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(jwkSource).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.issuer()));
        return decoder;
    }

    @Bean
    IdentityTokenCustomizer identityTokenCustomizer(TokenClaimsService tokenClaims) {
        return new IdentityTokenCustomizer(tokenClaims);
    }

    @Bean
    OAuth2TokenGenerator<?> tokenGenerator(JWKSource<SecurityContext> jwkSource, IdentityTokenCustomizer customizer,
                                           Clock clock) {
        // The JWKS also holds the public halves of keys being rotated out; only the one with a private half signs.
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(jwkSource);
        encoder.setJwkSelector(keys -> keys.stream().filter(JWK::isPrivate).findFirst().orElse(null));
        JwtGenerator jwt = new JwtGenerator(encoder);
        jwt.setJwtCustomizer(customizer);
        jwt.setClock(clock);
        return new DelegatingOAuth2TokenGenerator(jwt, new OAuth2AccessTokenGenerator(),
                new PublicClientAwareRefreshTokenGenerator(clock));
    }
}
