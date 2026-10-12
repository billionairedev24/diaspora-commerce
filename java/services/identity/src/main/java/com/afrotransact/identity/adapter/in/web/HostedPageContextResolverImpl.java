package com.afrotransact.identity.adapter.in.web;

import java.net.URI;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Component;

/**
 * Branding comes from the saved authorization request: the client id for the apps' own clients, and for the web BFF
 * ({@code gateway-bff}, one client for the three web apps) the origin of its redirect URI, which the authorization
 * endpoint has already checked against the client's registered URIs and which is the app's own origin
 * ({@code <app>/api/bff/v1/auth/oidc/callback}, following PUBLIC_WEB_URL / SELLER_CENTER_URL / STAFF_CONSOLE_URL).
 * Without a saved request, for an unknown client or for an origin that is none of the apps', the pages are
 * {@link HostedApp#NEUTRAL}: plain AfroTransact, never another app's branding. Seller Center requests may add
 * {@code intent} ({@code sell} or {@code provide}) for the "Start selling" / "Offer your services" copy.
 */
@Component
class HostedPageContextResolverImpl implements HostedPageContextResolver {

    private static final int MAX_HINT = 254;
    private static final int MAX_INVITATION = 200;

    private final RequestCache requestCache;
    private final LoginPageSettings settings;

    HostedPageContextResolverImpl(RequestCache requestCache, LoginPageSettings settings) {
        this.requestCache = requestCache;
        this.settings = settings;
    }

    @Override
    public HostedPageContext resolve(HttpServletRequest request, HttpServletResponse response) {
        SavedRequest saved = requestCache.getRequest(request, response);
        String clientId = param(saved, "client_id");
        HostedApp app = app(clientId, param(saved, "redirect_uri"));
        String appUrl = switch (app) {
            case SELLER -> settings.sellerCenterUrl();
            case STAFF -> settings.staffConsoleUrl();
            case NEUTRAL -> settings.homeUrl();
            default -> settings.storefrontUrl();
        };
        String hint = cut(param(saved, "login_hint"), MAX_HINT);
        String invitation = cut(param(saved, "invitation"), MAX_INVITATION);
        return new HostedPageContext(app, clientId, appUrl, hint, invitation, saved != null,
                intent(app, param(saved, "intent")), HostedPageContext.SIGNED_OUT.equals(param(saved, "notice")));
    }

    /** "Start selling" / "Offer your services": only for Seller Center requests, other values are dropped. */
    private static String intent(HostedApp app, String raw) {
        if (app != HostedApp.SELLER || raw == null) {
            return null;
        }
        String v = raw.toLowerCase(Locale.ROOT);
        return HostedPageContext.SELL.equals(v) || HostedPageContext.PROVIDE.equals(v) ? v : null;
    }

    /** The app an authorization request comes from; {@link HostedApp#NEUTRAL} when it cannot be told. */
    HostedApp app(String clientId, String redirectUri) {
        if (clientId == null) {
            return HostedApp.NEUTRAL;
        }
        return switch (clientId) {
            case "customer-app" -> HostedApp.SHOP;
            case "seller-center" -> HostedApp.SELLER;
            case "staff-console" -> HostedApp.STAFF;
            case "driver-app" -> HostedApp.DRIVER;
            case "mcp-client" -> HostedApp.AGENT;
            case "gateway-bff" -> byOrigin(redirectUri);
            default -> HostedApp.NEUTRAL;
        };
    }

    /** gateway-bff: the web app whose origin the (already validated) redirect URI is on. */
    private HostedApp byOrigin(String redirectUri) {
        String origin = origin(redirectUri);
        if (origin == null) {
            return HostedApp.NEUTRAL;
        }
        if (origin.equals(origin(settings.storefrontUrl()))) {
            return HostedApp.SHOP;
        }
        if (origin.equals(origin(settings.sellerCenterUrl()))) {
            return HostedApp.SELLER;
        }
        if (origin.equals(origin(settings.staffConsoleUrl()))) {
            return HostedApp.STAFF;
        }
        return HostedApp.NEUTRAL;
    }

    private static String origin(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI u = URI.create(url.trim());
            if (u.getScheme() == null || u.getHost() == null) {
                return null;
            }
            return (u.getScheme() + "://" + u.getHost() + (u.getPort() < 0 ? "" : ":" + u.getPort()))
                    .toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String param(SavedRequest saved, String name) {
        if (saved == null) {
            return null;
        }
        String[] values = saved.getParameterValues(name);
        return values == null || values.length == 0 || values[0].isBlank() ? null : values[0].trim();
    }

    private static String cut(String value, int max) {
        return value == null ? null : value.length() > max ? value.substring(0, max) : value;
    }
}
