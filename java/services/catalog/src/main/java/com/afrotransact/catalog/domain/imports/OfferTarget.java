package com.afrotransact.catalog.domain.imports;

import java.util.List;
import java.util.Set;

/**
 * The existing product page an offer row names (S393): found by barcode, ATIN or brand and part number. Filled by the catalog
 * match step; the validator only reads it.
 *
 * @param atin     the variant (child) the offer goes on; null when {@code kind} is not MATCH, or a brand plus part number
 *                 names a page with several children (AMBIGUOUS)
 * @param ownerOrg the organisation that owns the page; null when unknown
 * @param canOffer false with {@code blockers} when the organisation may not sell it
 * @param message  INVALID, NO_MATCH and AMBIGUOUS: the plain reason
 */
public record OfferTarget(Kind kind, String atin, String parentAtin, String title, String ownerOrg, boolean canOffer,
                          List<Blocker> blockers, String message) {

    /** Blockers that hold a row instead of failing it: the page or the seller will be cleared by people, not by the seller. */
    public static final Set<String> HELD_CODES = Set.of("offers.selling-approval-required", "catalog.page-not-live");

    public enum Kind {
        MATCH, NO_MATCH, INVALID, AMBIGUOUS
    }

    /** Why the organisation cannot offer: a stable code and the seller's words. */
    public record Blocker(String code, String message) {
    }

    public OfferTarget {
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
    }

    public static OfferTarget match(String atin, String parentAtin, String title, String ownerOrg, boolean canOffer,
                                    List<Blocker> blockers) {
        return new OfferTarget(Kind.MATCH, atin, parentAtin, title, ownerOrg, canOffer, blockers, null);
    }

    public static OfferTarget of(Kind kind, String message) {
        return new OfferTarget(kind, null, null, null, null, false, List.of(), message);
    }

    /** The row can be stored, but the offer cannot be on sale yet: approval or the page's own tax review is pending. */
    public boolean held() {
        return kind == Kind.MATCH && atin != null && !canOffer && !blockers.isEmpty()
                && blockers.stream().allMatch(b -> HELD_CODES.contains(b.code()));
    }

    /** The first blocker that neither holds nor allows the row. */
    public Blocker refusal() {
        return blockers.stream().filter(b -> !HELD_CODES.contains(b.code())).findFirst().orElse(null);
    }
}
