package com.afrotransact.catalog.domain;

/** Order of the public review list on a product page. */
public enum ReviewSort {
    /** Newest first (default). */
    NEWEST,
    /** Most stars first, newest first among equals. */
    HIGHEST,
    /** Fewest stars first, newest first among equals. */
    LOWEST
}
