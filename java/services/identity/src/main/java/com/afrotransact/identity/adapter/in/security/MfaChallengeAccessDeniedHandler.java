package com.afrotransact.identity.adapter.in.security;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.savedrequest.RequestCache;

/** A browser with a pending second factor that hits a protected page is sent to {@code /login/mfa}. */
public class MfaChallengeAccessDeniedHandler implements AccessDeniedHandler {

    private final RequestCache requestCache;
    private final AccessDeniedHandler fallback = new AccessDeniedHandlerImpl();

    public MfaChallengeAccessDeniedHandler(RequestCache requestCache) {
        this.requestCache = requestCache;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException denied)
            throws IOException, ServletException {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (current instanceof MfaPendingAuthentication) {
            if ("GET".equals(request.getMethod())) {
                requestCache.saveRequest(request, response);
            }
            response.sendRedirect(request.getContextPath() + MfaAwareAuthenticationSuccessHandler.MFA_PAGE);
            return;
        }
        fallback.handle(request, response, denied);
    }
}
