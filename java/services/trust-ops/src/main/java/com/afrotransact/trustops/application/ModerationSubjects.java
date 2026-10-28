package com.afrotransact.trustops.application;

import com.afrotransact.trustops.domain.ModerationItem;

/** Audit naming per queue item kind: listings are audited as LISTING / trustops.listing.*, reviews as REVIEW. */
final class ModerationSubjects {

    private ModerationSubjects() {
    }

    /** Audit entity type of the item's subject. */
    static String entityType(ModerationItem i) {
        return i.reviewItem() ? "REVIEW" : "LISTING";
    }

    /** Audit action, e.g. {@code trustops.listing.escalated} or {@code trustops.review.escalated}. */
    static String action(ModerationItem i, String verb) {
        return (i.reviewItem() ? "trustops.review." : "trustops.listing.") + verb;
    }
}
