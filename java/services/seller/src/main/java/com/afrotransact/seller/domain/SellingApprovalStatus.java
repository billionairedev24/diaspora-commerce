package com.afrotransact.seller.domain;

/** The state of a seller's approval for one scope. NOT_APPLIED is the absence of a row, so it is not stored. */
public enum SellingApprovalStatus {
    NOT_APPLIED("Not applied"),
    APPLIED("With our team"),
    NEEDS_ATTENTION("Needs your attention"),
    APPROVED("Approved"),
    EXPIRED("Expired"),
    NOT_AVAILABLE("Not available");

    private final String words;

    SellingApprovalStatus(String words) {
        this.words = words;
    }

    /** The status in plain words, as the seller reads it. */
    public String words() {
        return words;
    }
}
