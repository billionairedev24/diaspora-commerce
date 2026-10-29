package com.afrotransact.catalog.domain;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * One photo of a review: the public https URL media-service gave the uploaded, moderated image.
 *
 * @param url the image URL (https, at most {@value #MAX_URL} characters)
 */
public record ReviewPhoto(String url) {

    public static final int MAX_URL = 1024;

    public ReviewPhoto {
        Objects.requireNonNull(url, "url");
        url = url.strip();
    }

    /** The URL's host, lower case, or null when it is not an absolute URL. */
    public String host() {
        try {
            String host = URI.create(url).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** True for an absolute https URL of a sensible length. */
    public boolean valid() {
        return url.length() <= MAX_URL && url.startsWith("https://") && host() != null;
    }
}
