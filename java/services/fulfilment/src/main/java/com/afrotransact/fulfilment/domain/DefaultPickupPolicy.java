package com.afrotransact.fulfilment.domain;

/** Seller pickup rules from the driver-app design (not ready, closed, wrong count, damaged). */
public final class DefaultPickupPolicy implements PickupPolicy {

    static final int MIN_NOTE = 5;

    @Override
    public StopOutcome decide(PickupProblem p) {
        if (p == null || p.type() == null) {
            throw new FulfilmentRuleViolation("fulfilment.problem-type-required", "Choose what went wrong");
        }
        if (p.resolution() == null || !p.type().resolutions().contains(p.resolution())) {
            throw new FulfilmentRuleViolation("fulfilment.resolution-not-allowed", "For " + p.type()
                    + " choose one of " + p.type().resolutions());
        }
        if (Text.shorterThan(p.note(), MIN_NOTE)) {
            throw new FulfilmentRuleViolation("fulfilment.problem-note-required",
                    "Add a short note for the seller and dispatch");
        }
        if (p.type().photoRequired()) {
            if (!PhotoCapture.present(p.photo())) {
                throw new FulfilmentRuleViolation("fulfilment.problem-photo-required",
                        "A photo of the packages is required");
            }
            p.photo().requireLive("Pickup problem");
        }
        return switch (p.resolution()) {
            case WAIT, PARTIAL -> StopOutcome.CONTINUE;
            case SKIP -> StopOutcome.SKIPPED;
        };
    }

    @Override
    public void checkConfirmation(String handedOverBy, int expected, int scanned, String missingReason,
                                  boolean partialAccepted) {
        if (Text.shorterThan(handedOverBy, 2)) {
            throw new FulfilmentRuleViolation("fulfilment.handed-over-by-required",
                    "Enter who handed the packages over");
        }
        if (scanned == 0) {
            throw new FulfilmentRuleViolation("fulfilment.pickup-nothing-scanned",
                    "Scan the packages, or report a problem to skip this pickup");
        }
        if (scanned < expected && !partialAccepted && Text.shorterThan(missingReason, MIN_NOTE)) {
            throw new FulfilmentRuleViolation("fulfilment.pickup-scans-incomplete", "Scan every package ("
                    + scanned + "/" + expected + ") or give a reason for the missing ones");
        }
    }
}
