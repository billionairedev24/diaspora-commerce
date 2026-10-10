package com.afrotransact.content.domain.newsletter;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.content.domain.EmailAddress;

/**
 * Someone waiting for AfroTransact to open in a country.
 *
 * @param id              entry id
 * @param email           address to notify
 * @param country         ISO country code
 * @param audience        seller, provider or shopper
 * @param shoppingUpdates also wants shopping updates
 * @param createdAt       joined
 */
public record LaunchListEntry(UUID id, EmailAddress email, String country, LaunchAudience audience,
                              boolean shoppingUpdates, Instant createdAt) {
}
