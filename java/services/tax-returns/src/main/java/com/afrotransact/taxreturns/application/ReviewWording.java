package com.afrotransact.taxreturns.application;

import com.afrotransact.taxreturns.domain.profile.RecordStatus;

/** Plain words for why a classification record is not approved, for sellers and staff: no ids, no status codes. */
final class ReviewWording {

    private ReviewWording() {
    }

    /** Why a record in this status cannot be sold yet and who has to act. */
    static String notApproved(RecordStatus status) {
        return switch (status) {
            case PENDING_REVIEW -> "A tax reviewer has to look at this item before it can be sold. The review queue says why.";
            case MISSING_FACTS, DRAFT -> "The seller still has to answer the tax questions.";
            case REJECTED -> "Tax review sent this item back to the seller.";
            case STALE -> "The item's facts or category changed after it was approved, so the old decision no longer holds.";
            case APPROVED -> throw new IllegalArgumentException("An approved record has nothing to explain");
        };
    }
}
