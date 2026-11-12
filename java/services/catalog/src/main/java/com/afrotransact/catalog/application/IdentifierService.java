package com.afrotransact.catalog.application;

/** Barcode checks used by the product builder while the seller types, and GTIN lookups by service clients. */
public interface IdentifierService {

    /**
     * Validates a GTIN and finds the product page that uses it. Needs {@code catalog:read} (service clients such as
     * offers-service, any principal type) or {@code catalog:write} (sellers building a product).
     */
    GtinCheck check(String raw, boolean isbnRequired);

    /**
     * Result of a GTIN check.
     *
     * @param digits           cleaned digits (an ISBN-10 shown as its ISBN-13)
     * @param gtin14           the 14-digit match key (UPC and EAN of one item are equal); null when not valid
     * @param result           MATCH (a page already uses it), NO_MATCH, INVALID or RESTRICTED (store-use or coupon number)
     * @param valid            check digit and length valid (and ISBN prefix when required); a restricted number is not valid
     * @param suggestion       corrected code when only the check digit is wrong
     * @param existingAtin     ATIN of an existing product page with this GTIN (attach an offer instead)
     * @param message          human message for the form
     */
    record GtinCheck(String digits, String gtin14, String result, boolean valid, String suggestion,
                     String existingAtin, String message) {
    }
}
