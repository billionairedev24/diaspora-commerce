package com.afrotransact.content.domain.legal;

/**
 * A row of the cookie table.
 *
 * @param name     cookie name
 * @param provider who sets it
 * @param purpose  why
 * @param duration how long it lives
 * @param category category key
 */
public record CookieDefinition(String name, String provider, String purpose, String duration, String category) {
}
