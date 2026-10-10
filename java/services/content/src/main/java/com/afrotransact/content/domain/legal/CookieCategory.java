package com.afrotransact.content.domain.legal;

/**
 * A cookie category in the consent banner.
 *
 * @param key         necessary, functional, analytics, advertising…
 * @param name        display name
 * @param description what it is for
 * @param required    always on (strictly necessary)
 * @param gpcOptOut   switched off and locked when the browser sends Global Privacy Control
 * @param order       display order
 */
public record CookieCategory(String key, String name, String description, boolean required, boolean gpcOptOut,
                             int order) {
}
