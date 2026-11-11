package com.afrotransact.seller.domain;

import java.util.Optional;

/** Which step of the two-step review currently holds an application, derived from its status. */
public enum ReviewStage {
    REVIEW("applications:review", "review", "reviewer"),
    APPROVAL("applications:approve", "approval", "approver");

    private final String permission;
    private final String noun;
    private final String person;

    ReviewStage(String permission, String noun, String person) {
        this.permission = permission;
        this.noun = noun;
        this.person = person;
    }

    /** The permission a person needs to hold the case at this stage. */
    public String permission() {
        return permission;
    }

    /** "review" or "approval", for sentences such as "awaiting review from any reviewer". */
    public String noun() {
        return noun;
    }

    public String person() {
        return person;
    }

    /** The stage an application in {@code status} is at; empty when no staff member holds it (draft, with the applicant, decided). */
    public static Optional<ReviewStage> of(ApplicationStatus status) {
        return switch (status) {
            case PENDING_REVIEW -> Optional.of(REVIEW);
            case FLAGGED, AWAITING_FINAL_APPROVAL -> Optional.of(APPROVAL);
            default -> Optional.empty();
        };
    }
}
