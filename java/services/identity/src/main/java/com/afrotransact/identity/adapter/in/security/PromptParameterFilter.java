package com.afrotransact.identity.adapter.in.security;

import java.io.IOException;
import java.util.TreeMap;
import java.util.stream.Collectors;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * OIDC {@code prompt=login} (sign in again) and {@code prompt=create} (create an account) on
 * {@code GET /oauth2/authorize}: Spring Authorization Server would otherwise issue a code straight away for a browser
 * that already has an identity session (single sign-on). The first time such a request arrives the identity session
 * is ended, so the entry point shows the sign-in or sign-up page; the request is remembered in the session, and when
 * it is replayed after signing in it passes through once.
 */
public class PromptParameterFilter extends OncePerRequestFilter {

    /** Session attribute: the authorization request (its parameters) already prompted for. */
    static final String PROMPTED = "at.prompt.handled";

    private final RequestMatcher authorizeEndpoint;
    private final SecurityContextRepository contexts;

    public PromptParameterFilter(String authorizationEndpoint, SecurityContextRepository contexts) {
        this.authorizeEndpoint = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET,
                authorizationEndpoint);
        this.contexts = contexts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String prompt = request.getParameter("prompt");
        if (!authorizeEndpoint.matches(request) || !(HostedLoginEntryPoint.prompts(prompt, "login")
                || HostedLoginEntryPoint.prompts(prompt, "create"))) {
            chain.doFilter(request, response);
            return;
        }
        String query = requestKey(request);
        HttpSession session = request.getSession();
        if (query.equals(session.getAttribute(PROMPTED))) {
            session.removeAttribute(PROMPTED);
            chain.doFilter(request, response);
            return;
        }
        session.setAttribute(PROMPTED, query);
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            SecurityContext empty = SecurityContextHolder.createEmptyContext();
            SecurityContextHolder.setContext(empty);
            contexts.saveContext(empty, request, response);
        }
        chain.doFilter(request, response);
    }

    /**
     * The authorization request's parameters, sorted, without the request cache's {@code continue} marker that the
     * replayed request carries.
     */
    static String requestKey(HttpServletRequest request) {
        return new TreeMap<>(request.getParameterMap()).entrySet().stream()
                .filter(e -> !"continue".equals(e.getKey()))
                .map(e -> e.getKey() + "=" + String.join(",", e.getValue()))
                .collect(Collectors.joining("&"));
    }
}
