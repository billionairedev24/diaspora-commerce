package com.afrotransact.identity.adapter.in.security;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;

import com.afrotransact.identity.application.AuthSettingsService;
import com.afrotransact.identity.domain.AuthSettings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies the session timeouts of the auth settings (S291) to the hosted sign-in (single sign-on) session on every
 * request: the idle timeout becomes the session's inactive interval, and a session older than the absolute timeout is
 * ended however active it is. A changed setting therefore applies to existing sessions at their next request.
 */
public class SessionTimeoutFilter extends OncePerRequestFilter {

    private final AuthSettingsService settings;
    private final Clock clock;

    public SessionTimeoutFilter(AuthSettingsService settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            AuthSettings s = settings.effective();
            Instant created = Instant.ofEpochMilli(session.getCreationTime());
            if (created.plus(s.sessionAbsoluteTimeout()).isBefore(Instant.now(clock))) {
                session.invalidate();
            } else {
                int idleSeconds = (int) s.sessionIdleTimeout().toSeconds();
                if (session.getMaxInactiveInterval() != idleSeconds) {
                    session.setMaxInactiveInterval(idleSeconds);
                }
            }
        }
        chain.doFilter(request, response);
    }
}
