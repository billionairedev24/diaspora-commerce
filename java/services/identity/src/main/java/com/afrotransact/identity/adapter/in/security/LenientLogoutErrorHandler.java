package com.afrotransact.identity.adapter.in.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;

/**
 * RP-initiated logout ({@code /connect/logout}) whose ID token can no longer be matched to the browser's identity
 * session (the session id changed after signing in again, the token was rotated, the session expired): the person
 * still asked to sign out, so the identity session ends anyway. The browser then goes to the requested
 * {@code post_logout_redirect_uri} when it is registered for the named client, otherwise to the signed-out page.
 */
public class LenientLogoutErrorHandler implements AuthenticationFailureHandler {

    private static final Logger LOG = LoggerFactory.getLogger(LenientLogoutErrorHandler.class);
    static final String SIGNED_OUT = "/login?logout";

    private final RegisteredClientRepository clients;

    public LenientLogoutErrorHandler(RegisteredClientRepository clients) {
        this.clients = clients;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        LOG.debug("RP-initiated logout not matched to a session ({}); signing the browser out anyway",
                exception.getMessage());
        new SecurityContextLogoutHandler().logout(request, response, null);
        response.sendRedirect(target(request));
    }

    String target(HttpServletRequest request) {
        String clientId = request.getParameter("client_id");
        String uri = request.getParameter("post_logout_redirect_uri");
        if (clientId != null && uri != null) {
            RegisteredClient client = clients.findByClientId(clientId);
            if (client != null && client.getPostLogoutRedirectUris().contains(uri)) {
                return uri;
            }
        }
        return request.getContextPath() + SIGNED_OUT;
    }
}
