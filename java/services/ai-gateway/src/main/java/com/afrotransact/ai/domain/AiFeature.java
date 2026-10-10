package com.afrotransact.ai.domain;

import java.util.Locale;

/** Product feature a model call is billed to; budgets are enforced per feature and per user. */
public enum AiFeature {
    LOLA,
    EMBEDDINGS,
    LISTING_WRITER,
    TRANSLATE,
    TAGLINE,
    TRIAGE,
    HANDOFF_SUMMARY;

    /** Configuration and ledger key, for example {@code listing-writer}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static AiFeature fromKey(String key) {
        return valueOf(key.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
