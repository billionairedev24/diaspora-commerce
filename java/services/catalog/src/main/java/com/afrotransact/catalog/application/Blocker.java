package com.afrotransact.catalog.application;

/**
 * Why an organisation cannot do something, in plain words: a stable {@code code} for the client and a {@code message}
 * for the seller.
 */
public record Blocker(String code, String message) {
}
