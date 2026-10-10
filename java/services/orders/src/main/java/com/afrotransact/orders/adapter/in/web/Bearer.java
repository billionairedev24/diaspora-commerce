package com.afrotransact.orders.adapter.in.web;

/** Extracts the raw access token from an Authorization header (relayed to cart and identity). */
final class Bearer {

    private Bearer() {
    }

    static String of(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        return authorization.substring(7).trim();
    }
}
