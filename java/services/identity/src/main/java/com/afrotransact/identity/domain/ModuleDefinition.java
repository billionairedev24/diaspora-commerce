package com.afrotransact.identity.domain;

/** A back-office module (menu entry) of a portal. */
public record ModuleDefinition(Portal portal, String key, String label, int position) {
}
