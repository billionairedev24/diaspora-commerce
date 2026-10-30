package com.afrotransact.catalog.domain.brand;

/** Where a brand stands in the registry. */
public enum BrandStatus {
    /** Reviewed (or listed by staff): offered to every seller in its scope. */
    APPROVED,
    /** Typed in by a seller: usable at once by that seller, awaiting staff review. */
    PENDING,
    /** Staff declined it: not offered; products that hold it must pick another brand when edited. */
    REJECTED,
    /** Folded into another brand; its products show the other brand's name. */
    MERGED_INTO,
    /** Withdrawn by staff (no longer sold, or listed by mistake): not offered for new listings. */
    RETIRED;

    /** Brands that can be matched by name when a seller types one: approved, or pending (for its author). */
    public boolean live() {
        return this == APPROVED || this == PENDING;
    }
}
