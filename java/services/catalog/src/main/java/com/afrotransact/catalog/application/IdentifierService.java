package com.afrotransact.catalog.application;

/** Barcode checks used by the product builder while the seller types. */
public interface IdentifierService {

    GtinCheck check(String raw, boolean isbnRequired);

    /**
     * Result of a GTIN check.
     *
     * @param digits           cleaned digits
     * @param valid            check digit and length valid (and ISBN prefix when required)
     * @param suggestion       corrected code when only the check digit is wrong
     * @param existingAtin     ATIN of an existing product page with this GTIN (attach an offer instead)
     * @param message          human message for the form
     */
    record GtinCheck(String digits, boolean valid, String suggestion, String existingAtin, String message) {
    }
}
