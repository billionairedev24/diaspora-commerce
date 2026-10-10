package com.afrotransact.identity.adapter.in.security;

import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.util.StringUtils;

/**
 * Recognises {@code grant_type=refresh_token} requests from public clients (mobile apps): {@code client_id} only,
 * no secret, no Authorization header. Spring Authorization Server otherwise only authenticates public clients for
 * the authorization-code exchange with PKCE.
 */
public class PublicClientRefreshTokenAuthenticationConverter implements AuthenticationConverter {

    @Override
    public Authentication convert(HttpServletRequest request) {
        if (!AuthorizationGrantType.REFRESH_TOKEN.getValue().equals(request.getParameter(OAuth2ParameterNames.GRANT_TYPE))
                || request.getHeader(HttpHeaders.AUTHORIZATION) != null
                || request.getParameter(OAuth2ParameterNames.CLIENT_SECRET) != null
                || request.getParameter(OAuth2ParameterNames.CLIENT_ASSERTION) != null) {
            return null;
        }
        String clientId = request.getParameter(OAuth2ParameterNames.CLIENT_ID);
        if (!StringUtils.hasText(clientId)) {
            return null;
        }
        Map<String, Object> params = new HashMap<>();
        request.getParameterMap().forEach((k, v) -> {
            if (!OAuth2ParameterNames.CLIENT_ID.equals(k)) {
                params.put(k, v.length == 1 ? v[0] : v);
            }
        });
        return new OAuth2ClientAuthenticationToken(clientId, ClientAuthenticationMethod.NONE, null, params);
    }
}
