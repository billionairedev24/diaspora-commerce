package com.afrotransact.identity.adapter.in.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code GET /oauth2/authorize?…&login_ticket=…}: the BFF redeems a first-party login ticket on the normal
 * authorization-code flow. A valid ticket authenticates the account for this request only (nothing is saved in a
 * session), so the authorization endpoint issues the code to the client's registered redirect URI, and the BFF
 * exchanges it at {@code /oauth2/token} with its secret and PKCE verifier. An invalid ticket gets a 400
 * {@code invalid_grant} JSON error instead of the sign-in page.
 *
 * <p>Runs right after the security context is loaded, before the authorization request is pre-validated (which
 * captures the principal).
 */
public class LoginTicketAuthenticationFilter extends OncePerRequestFilter {

    public static final String PARAMETER = "login_ticket";
    private static final String CLIENT_ID = "client_id";

    private final AuthenticationManager authenticationManager;
    private final RequestMatcher authorizeEndpoint;
    private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

    public LoginTicketAuthenticationFilter(AuthenticationManager authenticationManager, String authorizationEndpoint) {
        this.authenticationManager = authenticationManager;
        this.authorizeEndpoint = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET,
                authorizationEndpoint);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ticket = request.getParameter(PARAMETER);
        if (ticket == null || !authorizeEndpoint.matches(request)) {
            chain.doFilter(request, response);
            return;
        }
        Authentication result;
        try {
            result = authenticationManager.authenticate(new LoginTicketAuthenticationToken(ticket,
                    request.getParameter(CLIENT_ID)));
        } catch (AuthenticationException ex) {
            contexts.clearContext();
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"error\":\"invalid_grant\",\"error_description\":"
                    + "\"The login ticket is invalid, expired or was already used. Sign in again.\"}");
            return;
        }
        SecurityContext context = contexts.createEmptyContext();
        context.setAuthentication(result);
        contexts.setContext(context);
        chain.doFilter(request, response);
    }
}
