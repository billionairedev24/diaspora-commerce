package com.afrotransact.catalog.domain;

import java.util.Locale;

/** What the drafts table can be ordered by. */
public enum DraftSortField {
    UPDATED_AT, TITLE, CATEGORY;

    /** Parses the API's {@code updatedAt | title | categoryPath}; anything else is the default, the saved date. */
    public static DraftSortField parse(String name) {
        if (name == null) {
            return UPDATED_AT;
        }
        return switch (name.strip().toLowerCase(Locale.ROOT)) {
            case "title" -> TITLE;
            case "categorypath", "category" -> CATEGORY;
            default -> UPDATED_AT;
        };
    }
}
