package com.afrotransact.catalog.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The thresholds of matching, no-barcode exemptions and the staff review queue (S381 to S387). Nothing here is hard-coded
 * in the services: defaults come from configuration, staff change them (catalog:moderate) and the stored copy wins.
 *
 * @param reservedPrefixes                       store-use and coupon prefixes, compared with the start of the GTIN-13 form
 * @param mpnAttributeKey                        the product attribute that holds the manufacturer part number
 * @param reviewSlaBusinessDays                  business days staff have to answer a request
 * @param alertAfterBusinessDays                 the oldest open request that raises the alert
 * @param noteMaxLength                          the longest note a seller may add to a request
 * @param knownGs1BrandMinPages                  pages carrying barcodes that make a brand a "known GS1 brand"
 * @param exemptionGatedCategoryCodes            product types (or category codes) that are brand-gated
 * @param exemptionRequiresApprovalForKnownBrands whether a no-barcode item of a known GS1 brand needs staff approval
 * @param updatedBy                              who saved it (null for the configured defaults)
 * @param updatedAt                              when (null for the configured defaults)
 * @param version                                optimistic-locking version of the stored copy (0 for the defaults)
 */
public record CatalogPolicy(List<String> reservedPrefixes, String mpnAttributeKey, int reviewSlaBusinessDays,
                            int alertAfterBusinessDays, int noteMaxLength, int knownGs1BrandMinPages,
                            List<String> exemptionGatedCategoryCodes, boolean exemptionRequiresApprovalForKnownBrands,
                            String updatedBy, Instant updatedAt, long version) {

    private static final Pattern PREFIX = Pattern.compile("[0-9]{2,6}");
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{0,59}");

    public CatalogPolicy {
        reservedPrefixes = reservedPrefixes == null ? List.of() : List.copyOf(reservedPrefixes);
        exemptionGatedCategoryCodes = exemptionGatedCategoryCodes == null ? List.of()
                : List.copyOf(exemptionGatedCategoryCodes);
    }

    /** Why the values cannot be saved, by field; empty when they can. */
    public Map<String, String> problems() {
        Map<String, String> errors = new LinkedHashMap<>();
        for (String prefix : reservedPrefixes) {
            if (prefix == null || !PREFIX.matcher(prefix).matches()) {
                errors.put("reservedPrefixes", "Each prefix is 2 to 6 digits: " + prefix);
                break;
            }
        }
        if (mpnAttributeKey == null || !KEY.matcher(mpnAttributeKey).matches()) {
            errors.put("mpnAttributeKey", "A lower-case attribute key such as model");
        }
        if (reviewSlaBusinessDays < 1 || reviewSlaBusinessDays > 30) {
            errors.put("reviewSlaBusinessDays", "Between 1 and 30");
        }
        if (alertAfterBusinessDays < 1 || alertAfterBusinessDays > 60) {
            errors.put("alertAfterBusinessDays", "Between 1 and 60");
        } else if (alertAfterBusinessDays < reviewSlaBusinessDays) {
            errors.put("alertAfterBusinessDays", "Not before the review time of " + reviewSlaBusinessDays + " days");
        }
        if (noteMaxLength < 50 || noteMaxLength > 5000) {
            errors.put("noteMaxLength", "Between 50 and 5000");
        }
        if (knownGs1BrandMinPages < 1) {
            errors.put("knownGs1BrandMinPages", "At least 1");
        }
        return errors;
    }

    /** The same settings stored by {@code by} at {@code at}; the repository sets the version. */
    public CatalogPolicy savedBy(String by, Instant at) {
        return new CatalogPolicy(reservedPrefixes, mpnAttributeKey, reviewSlaBusinessDays, alertAfterBusinessDays,
                noteMaxLength, knownGs1BrandMinPages, exemptionGatedCategoryCodes,
                exemptionRequiresApprovalForKnownBrands, by, at, version);
    }
}
