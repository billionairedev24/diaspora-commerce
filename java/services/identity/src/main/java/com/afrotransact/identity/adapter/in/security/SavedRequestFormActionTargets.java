package com.afrotransact.identity.adapter.in.security;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;

/**
 * The origin of the saved authorization request's {@code redirect_uri}, only when that exact URI is registered for the
 * request's client (so a crafted authorize URL cannot widen the policy), plus the origin of the post-sign-in home URL
 * for sign-ins without a pending request.
 */
public class SavedRequestFormActionTargets implements FormActionTargets {

    private final RequestCache requestCache;
    private final RegisteredClientRepository clients;
    private final String homeUrl;

    public SavedRequestFormActionTargets(RequestCache requestCache, RegisteredClientRepository clients, String homeUrl) {
        this.requestCache = requestCache;
        this.clients = clients;
        this.homeUrl = homeUrl;
    }

    @Override
    public List<String> sources(HttpServletRequest request, HttpServletResponse response) {
        List<String> out = new ArrayList<>(2);
        add(out, source(homeUrl));
        SavedRequest saved = requestCache.getRequest(request, response);
        if (saved != null) {
            String clientId = first(saved, "client_id");
            String redirectUri = first(saved, "redirect_uri");
            RegisteredClient client = clientId == null ? null : clients.findByClientId(clientId);
            if (client != null && redirectUri != null && client.getRedirectUris().contains(redirectUri)) {
                add(out, source(redirectUri));
            }
        }
        return out;
    }

    private static void add(List<String> out, String source) {
        if (source != null && !out.contains(source)) {
            out.add(source);
        }
    }

    private static String first(SavedRequest saved, String name) {
        String[] values = saved.getParameterValues(name);
        return values == null || values.length == 0 || values[0].isBlank() ? null : values[0].trim();
    }

    /** {@code https://host[:port]} for web URLs, {@code scheme:} for custom schemes, null for anything unusable. */
    public static String source(String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        try {
            URI u = URI.create(uri.trim());
            String scheme = u.getScheme();
            if (scheme == null || !scheme.matches("[A-Za-z][A-Za-z0-9+.-]*")) {
                return null;
            }
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return scheme.equals("javascript") || scheme.equals("data") ? null : scheme + ":";
            }
            String host = u.getHost();
            if (host == null || !host.matches("[A-Za-z0-9.\\-]+|\\[[0-9A-Fa-f:.]+\\]")) {
                return null;
            }
            return scheme + "://" + host.toLowerCase(Locale.ROOT) + (u.getPort() < 0 ? "" : ":" + u.getPort());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
