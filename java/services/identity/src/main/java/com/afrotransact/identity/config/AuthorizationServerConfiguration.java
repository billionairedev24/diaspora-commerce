package com.afrotransact.identity.config;

import java.time.Clock;
import java.util.List;

import com.afrotransact.identity.adapter.in.security.IdentityTokenCustomizer;
import com.afrotransact.identity.adapter.in.security.PublicClientAwareRefreshTokenGenerator;
import com.afrotransact.identity.application.TokenClaimsService;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

/** Spring Authorization Server: JDBC stores on PostgreSQL, RS256 keys, token generation with platform claims. */
@Configuration(proxyBeanMethods = false)
class AuthorizationServerConfiguration {

    @Bean
    RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbc) {
        return new JdbcRegisteredClientRepository(jdbc);
    }

    @Bean
    OAuth2AuthorizationService authorizationService(JdbcTemplate jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationService(jdbc, clients);
    }

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(JdbcTemplate jdbc,
                                                                  RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(jdbc, clients);
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(IdentityProperties props) {
        return AuthorizationServerSettings.builder().issuer(props.issuer()).build();
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(IdentityProperties props) {
        List<JWK> keys = SigningKeys.load(props.signing());
        return new ImmutableJWKSet<>(new JWKSet(keys));
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
        JwtGenerator jwt = new JwtGenerator(new NimbusJwtEncoder(jwkSource));
        jwt.setJwtCustomizer(customizer);
        jwt.setClock(clock);
        return new DelegatingOAuth2TokenGenerator(jwt, new OAuth2AccessTokenGenerator(),
                new PublicClientAwareRefreshTokenGenerator(clock));
    }
}
