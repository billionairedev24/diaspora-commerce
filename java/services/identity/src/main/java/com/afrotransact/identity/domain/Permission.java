package com.afrotransact.identity.domain;

/** An individual ability such as {@code catalog:write} or {@code delivery:operate}. */
public record Permission(String key, String description, String module) {
}
