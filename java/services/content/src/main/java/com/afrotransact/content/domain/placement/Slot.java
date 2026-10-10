package com.afrotransact.content.domain.placement;

/**
 * A placement slot in the catalogue.
 *
 * @param key           slot key
 * @param name          display name
 * @param description   where it renders
 * @param maxItems      how many placements one context receives at most (highest priority first)
 * @param sizeSpec      creative size and weight limits
 * @param payloadSchema JSON Schema (2020-12) every placement payload in this slot must satisfy
 */
public record Slot(SlotKey key, String name, String description, int maxItems, String sizeSpec,
                   String payloadSchema) {
}
