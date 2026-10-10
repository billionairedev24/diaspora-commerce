package com.afrotransact.catalog.application;

import java.util.Objects;

/**
 * Label settings.
 *
 * @param retailOrgId organisation id of AfroTransact Retail (platform stock keeps its vendor GTIN on unit labels)
 */
public record LabelSettings(String retailOrgId) {

    public LabelSettings {
        Objects.requireNonNull(retailOrgId, "retailOrgId");
    }
}
