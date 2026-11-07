package com.afrotransact.identity.adapter.in.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.header.HeaderWriter;

/**
 * Enforced Content-Security-Policy of the hosted pages (and every other response of this server). The pages ship no
 * inline script or style, no third-party host and no fonts or images from elsewhere, so everything is {@code 'self'}.
 * {@code form-action} additionally allows the pending sign-in's redirect target (see {@link FormActionTargets}).
 * <p>
 * The interactive API reference pages ({@code /docs}, {@code /redoc}, Swagger UI) are third-party rendered with inline
 * code and are exempt; they hold no credentials and are not served in production.
 */
public class HostedPageContentSecurityPolicy implements HeaderWriter {

    public static final String HEADER = "Content-Security-Policy";

    /** Everything except {@code form-action}, which is appended per request. */
    static final String BASE = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "font-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; object-src 'none'";

    private static final Set<String> API_REFERENCE_PAGES = Set.of("/docs", "/redoc", "/swagger-ui.html");

    private final FormActionTargets formActionTargets;

    public HostedPageContentSecurityPolicy(FormActionTargets formActionTargets) {
        this.formActionTargets = formActionTargets;
    }

    @Override
    public void writeHeaders(HttpServletRequest request, HttpServletResponse response) {
        if (response.containsHeader(HEADER) || isApiReference(request)) {
            return;
        }
        response.setHeader(HEADER, policy(formActionTargets.sources(request, response)));
    }

    static String policy(List<String> formActionSources) {
        List<String> sources = new ArrayList<>();
        sources.add("'self'");
        sources.addAll(formActionSources);
        return BASE + "; form-action " + String.join(" ", sources);
    }

    private static boolean isApiReference(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return API_REFERENCE_PAGES.contains(path) || path.startsWith("/swagger-ui/");
    }
}
