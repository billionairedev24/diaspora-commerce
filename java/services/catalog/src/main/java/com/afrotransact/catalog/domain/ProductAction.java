package com.afrotransact.catalog.domain;

import java.util.Locale;

/** A staff or seller action on a product page whose validity depends on the page's status (the catalog's state machine). */
public enum ProductAction {
    APPROVE,
    REJECT,
    PAUSE,
    RESUME,
    TAKEDOWN,
    RESTORE;

    /** The wire name, for example {@code takedown}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
