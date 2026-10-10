package com.afrotransact.catalog.domain.barcode;

/** Turns a {@link LabelDraft} into a validated, encoded {@link Label}. */
public interface LabelPolicy {

    /**
     * Picks the symbology for the label kind, validates the data (GTIN check digits, GS1 AI rules) and encodes it.
     *
     * @throws com.afrotransact.catalog.domain.CatalogRuleViolation with per-field errors when the draft is invalid
     */
    Label build(LabelDraft draft);
}
