package com.afrotransact.identity.adapter.in.security;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * Authenticates public clients on the refresh-token grant when the client opted in with the
 * {@link #PUBLIC_REFRESH_TOKENS} client setting. Refresh tokens rotate on every use ({@code reuseRefreshTokens=false})
 * so a stolen, already-used refresh token is rejected.
 */
public class PublicClientRefreshTokenAuthenticationProvider implements AuthenticationProvider {

    /** Client setting that allows refresh tokens for a public (PKCE) client. */
    public static final String PUBLIC_REFRESH_TOKENS = "settings.client.afrotransact.public-refresh-tokens";

    private final RegisteredClientRepository clients;

    public PublicClientRefreshTokenAuthenticationProvider(RegisteredClientRepository clients) {
        this.clients = clients;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        OAuth2ClientAuthenticationToken token = (OAuth2ClientAuthenticationToken) authentication;
        if (!ClientAuthenticationMethod.NONE.equals(token.getClientAuthenticationMethod())
                || !AuthorizationGrantType.REFRESH_TOKEN.getValue()
                        .equals(token.getAdditionalParameters().get(OAuth2ParameterNames.GRANT_TYPE))) {
            return null;
        }
        RegisteredClient client = clients.findByClientId(String.valueOf(token.getPrincipal()));
        if (client == null || !allowsPublicRefresh(client)) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
        }
        return new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
    }

    static boolean allowsPublicRefresh(RegisteredClient client) {
        return client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)
                && client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN)
                && Boolean.TRUE.equals(client.getClientSettings().getSetting(PUBLIC_REFRESH_TOKENS));
    }
}
