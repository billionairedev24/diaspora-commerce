package com.afrotransact.seller.domain;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Which logo addresses a business page may show. The logo is whatever address the media service handed back after the
 * upload: https on the media CDN (R2) in dev, uat and production, but plain {@code http://localhost:9000/media/...} on a
 * developer machine (or {@code http://192.168.x.y} when a phone opens the local stack over the LAN). So https is required
 * for public hosts and http is accepted only for hosts that cannot be reached from the internet.
 */
public final class LogoUrlRules {

    public static final int MAX_LENGTH = 2048;

    private static final Pattern IP_LITERAL = Pattern.compile("^(\\d{1,3}(\\.\\d{1,3}){3}|\\[[0-9a-fA-F:.]+\\])$");

    private LogoUrlRules() {
    }

    /** True when the address is an image URL a page may embed (https, or http on a local-only host). */
    public static boolean accepts(String url) {
        if (url == null || url.length() > MAX_LENGTH || url.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null || host.isBlank() || uri.getUserInfo() != null) {
            return false;
        }
        return switch (scheme) {
            case "https" -> true;
            case "http" -> isLocalOnly(host);
            default -> false;
        };
    }

    static boolean isLocalOnly(String rawHost) {
        String host = rawHost.toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".localhost") || host.equals("host.docker.internal")) {
            return true;
        }
        if (!IP_LITERAL.matcher(host).matches()) {
            return false;
        }
        try {
            InetAddress a = InetAddress.getByName(host.replace("[", "").replace("]", ""));
            return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
