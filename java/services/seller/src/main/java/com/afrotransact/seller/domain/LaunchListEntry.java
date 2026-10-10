package com.afrotransact.seller.domain;

import java.time.Instant;
import java.util.UUID;

/** Someone who wants to sell in a country that is not live yet. */
public record LaunchListEntry(UUID id, String country, String email, boolean shoppingUpdates, Instant createdAt) {
}
