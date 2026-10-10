package com.afrotransact.identity.adapter.in.security;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.application.SignInAttempt;
import com.afrotransact.identity.application.SignInProtectionService;
import com.afrotransact.identity.domain.RateLimitExceeded;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Brute-force protection for the hosted password form ({@code POST /login}): the same per-account and per-IP
 * counters and lockouts as the first-party API, so neither path can be used to bypass the other. A locked attempt
 * is not even checked against the password and lands on {@code /login?error=locked}.
 */
public class HostedLoginProtection extends OncePerRequestFilter implements AuthenticationFailureHandler {

    public static final String LOCKED_URL = "/login?error=locked";
    private static final String FAILED_URL = "/login?error";
    private static final String USERNAME = "username";

    private final SignInProtectionService protection;
    private final RequestMatcher loginPost = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST,
            "/login");

    public HostedLoginProtection(SignInProtectionService protection) {
        this.protection = protection;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (loginPost.matches(request)) {
            try {
                protection.requireNotLocked(attempt(request, null, email(request), "PASSWORD", null));
            } catch (RateLimitExceeded locked) {
                response.sendRedirect(request.getContextPath() + LOCKED_URL);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        if (exception instanceof BadCredentialsException) {
            Optional<?> locked = protection.recordFailure(attempt(request, null, email(request), "PASSWORD",
                    "bad-credentials"));
            if (locked.isPresent()) {
                response.sendRedirect(request.getContextPath() + LOCKED_URL);
                return;
            }
        }
        response.sendRedirect(request.getContextPath() + FAILED_URL);
    }

    /** The hosted password form succeeded and no second factor is pending. */
    public void succeeded(HttpServletRequest request, UUID userId) {
        protection.recordSuccess(attempt(request, userId, email(request), "PASSWORD", null));
    }

    private static SignInAttempt attempt(HttpServletRequest request, UUID userId, String email, String method,
                                         String reason) {
        return new SignInAttempt(userId, email, request.getRemoteAddr(), request.getHeader("User-Agent"), null,
                SignInAttempt.HOSTED, method, reason);
    }

    private static String email(HttpServletRequest request) {
        String username = request.getParameter(USERNAME);
        return username == null || username.isBlank() ? null : username.trim().toLowerCase(Locale.ROOT);
    }
}
