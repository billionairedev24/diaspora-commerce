package com.afrotransact.identity.domain;

import java.util.Locale;

/** Organisation approval status, mirrored from the seller service's two-step review. */
public enum OrgStatus {
    PENDING,
    APPROVED,
    SUSPENDED,
    REJECTED;

    /**
     * Maps a seller-service organisation status (DRAFT, PENDING_REVIEW, FLAGGED, AWAITING_FINAL_APPROVAL,
     * INFO_REQUESTED, APPROVED, REJECTED, SUSPENDED) to the identity mirror; everything still in review is PENDING.
     */
    public static OrgStatus fromSeller(String sellerStatus) {
        if (sellerStatus == null) {
            return PENDING;
        }
        return switch (sellerStatus.trim().toUpperCase(Locale.ROOT)) {
            case "APPROVED" -> APPROVED;
            case "SUSPENDED" -> SUSPENDED;
            case "REJECTED" -> REJECTED;
            default -> PENDING;
        };
    }
}
