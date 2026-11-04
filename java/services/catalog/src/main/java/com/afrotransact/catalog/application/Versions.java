package com.afrotransact.catalog.application;

import com.afrotransact.catalog.domain.PreconditionRequiredException;
import com.afrotransact.catalog.domain.StaleVersionException;

/** Optimistic locking for staff edits: the version the editor loaded must still be the stored one. */
final class Versions {

    private Versions() {
    }

    /**
     * @param expected the version named by {@code If-Match}
     * @param actual   the stored version
     * @param what     "category", "product type"… for the message
     * @throws PreconditionRequiredException when no version was named
     * @throws StaleVersionException         when it is not the stored one
     */
    static void check(Long expected, long actual, String what) {
        if (expected == null) {
            throw new PreconditionRequiredException("Send the version you loaded in If-Match");
        }
        if (expected != actual) {
            throw new StaleVersionException("This " + what + " changed since you opened it. Reload and try again.");
        }
    }
}
