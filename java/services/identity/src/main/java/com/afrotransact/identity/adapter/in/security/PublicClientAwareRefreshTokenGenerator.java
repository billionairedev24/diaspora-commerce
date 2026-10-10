package com.afrotransact.identity.adapter.in.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.OAuth2RefreshTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

/**
 * Same as {@link OAuth2RefreshTokenGenerator} but also issues refresh tokens to public clients that opted in
 * (mobile apps) on the authorization-code exchange.
 */
public class PublicClientAwareRefreshTokenGenerator implements OAuth2TokenGenerator<OAuth2RefreshToken> {

    private final OAuth2RefreshTokenGenerator delegate = new OAuth2RefreshTokenGenerator();
    private final StringKeyGenerator keys = new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), 96);
    private final Clock clock;

    public PublicClientAwareRefreshTokenGenerator(Clock clock) {
        this.clock = clock;
        delegate.setClock(clock);
    }

    @Override
    public OAuth2RefreshToken generate(OAuth2TokenContext context) {
        if (OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())
                && AuthorizationGrantType.AUTHORIZATION_CODE.equals(context.getAuthorizationGrantType())
                && PublicClientRefreshTokenAuthenticationProvider.allowsPublicRefresh(context.getRegisteredClient())) {
            Instant issuedAt = clock.instant();
            return new OAuth2RefreshToken(keys.generateKey(), issuedAt,
                    issuedAt.plus(context.getRegisteredClient().getTokenSettings().getRefreshTokenTimeToLive()));
        }
        return delegate.generate(context);
    }
}
