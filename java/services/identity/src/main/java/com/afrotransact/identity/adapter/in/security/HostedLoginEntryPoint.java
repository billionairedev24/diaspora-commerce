package com.afrotransact.identity.adapter.in.security;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;

import com.afrotransact.identity.application.IdentityProviderService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Where an unauthenticated browser goes from {@code /oauth2/authorize} (the request is already saved, so signing in
 * continues it):
 * <ul>
 *   <li>{@code idp_hint=google|apple|facebook} for an enabled provider: straight to that provider;</li>
 *   <li>{@code prompt=create} (OIDC "create account"): the hosted sign-up page;</li>
 *   <li>otherwise the hosted sign-in page.</li>
 * </ul>
 */
public class HostedLoginEntryPoint implements AuthenticationEntryPoint {

    public static final String LOGIN_PAGE = "/login";
    public static final String SIGN_UP_PAGE = "/signup";

    private final IdentityProviderService providers;

    public HostedLoginEntryPoint(IdentityProviderService providers) {
        this.providers = providers;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        response.sendRedirect(request.getContextPath() + target(request));
    }

    String target(HttpServletRequest request) {
        String idp = request.getParameter("idp_hint");
        if (idp != null && !idp.isBlank()) {
            String id = idp.trim().toLowerCase(Locale.ROOT);
            if (id.matches("[a-z]{2,20}") && providers.isEnabled(id)) {
                return "/oauth2/authorization/" + id;
            }
        }
        return prompts(request.getParameter("prompt"), "create") ? SIGN_UP_PAGE : LOGIN_PAGE;
    }

    /** Whether the space-separated OIDC {@code prompt} parameter contains the value. */
    static boolean prompts(String prompt, String value) {
        return prompt != null && Arrays.asList(prompt.trim().split("\\s+")).contains(value);
    }
}
