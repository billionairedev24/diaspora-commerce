package com.afrotransact.catalog.domain;

/** What a seller asks staff to do to a product page they do not own (S385). */
public enum ChangeRequestType {
    IMAGE_ADD(true, true),
    IMAGE_REPLACE(true, true),
    TITLE(true, false),
    ATTRIBUTE(true, false),
    DESCRIPTION(true, false),
    CATEGORY(true, false),
    BRAND(true, false),
    NEW_VARIANT(true, false),
    /** The seller was shown the match card and answered "not my product". */
    WRONG_MATCH(false, false),
    /** Two pages describe one item. */
    DUPLICATE_PAGE(false, false);

    private final boolean needsOffer;
    private final boolean image;

    ChangeRequestType(boolean needsOffer, boolean image) {
        this.needsOffer = needsOffer;
        this.image = image;
    }

    /** True when only a seller with an open offer on the page may ask (reports of a mistake need none). */
    public boolean needsOffer() {
        return needsOffer;
    }

    /** True for the two image types (they pass the image checks before they are applied). */
    public boolean image() {
        return image;
    }

    /** True when approving changes the page content directly (the rest are resolved by staff in other ways). */
    public boolean editsContent() {
        return this != WRONG_MATCH && this != DUPLICATE_PAGE && this != NEW_VARIANT;
    }
}
