package com.afrotransact.fulfilment.domain;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Drop-off problem rules from the driver-app design. "Customer unavailable" needs a call, a text and a completed
 * wait ({@code wait}, 5 minutes) before reattempt, pickup point or return. Access issues, damage and unsafe need a
 * photo; every type except customer-unavailable needs a note (5+ characters). A stop can be set to retry at most
 * {@code maxReattempts} times; after that the package goes to a pickup point or back to the FC.
 */
public final class DefaultReattemptPolicy implements ReattemptPolicy {

    public static final Duration DEFAULT_WAIT = Duration.ofMinutes(5);
    public static final int DEFAULT_MAX_REATTEMPTS = 2;
    static final int MIN_NOTE = 5;

    private final Duration wait;
    private final int maxReattempts;

    public DefaultReattemptPolicy(Duration wait, int maxReattempts) {
        this.wait = wait;
        this.maxReattempts = maxReattempts;
    }

    public DefaultReattemptPolicy() {
        this(DEFAULT_WAIT, DEFAULT_MAX_REATTEMPTS);
    }

    @Override
    public ProblemDecision decide(DeliveryProblem p, List<ContactAttempt> contacts, int reattemptsSoFar) {
        if (p == null || p.type() == null) {
            throw new FulfilmentRuleViolation("fulfilment.problem-type-required", "Choose what went wrong");
        }
        if (p.resolution() == null || !p.type().resolutions().contains(p.resolution())) {
            throw new FulfilmentRuleViolation("fulfilment.resolution-not-allowed", "For " + p.type()
                    + " choose one of " + p.type().resolutions());
        }
        if (p.at() == null) {
            throw new FulfilmentRuleViolation("fulfilment.time-required", "The report needs its capture time");
        }
        if (p.type() == DeliveryProblemType.CUSTOMER_UNAVAILABLE) {
            requireContactPolicy(contacts, p);
        }
        if (p.type().noteRequired() && Text.shorterThan(p.note(), MIN_NOTE)) {
            throw new FulfilmentRuleViolation("fulfilment.problem-note-required",
                    "Add a short note for the customer and dispatch");
        }
        if (p.type().photoRequired()) {
            if (!PhotoCapture.present(p.photo())) {
                throw new FulfilmentRuleViolation("fulfilment.problem-photo-required",
                        "A photo of the problem is required");
            }
            p.photo().requireLive("Problem");
        }
        String reason = p.type().name().toLowerCase(Locale.ROOT);
        return switch (p.resolution()) {
            case REATTEMPT -> {
                if (reattemptsSoFar >= maxReattempts) {
                    throw new FulfilmentRuleViolation("fulfilment.reattempt-limit", "This stop was already retried "
                            + reattemptsSoFar + " times; take it to a pickup point or return it to the FC");
                }
                yield new ProblemDecision(StopOutcome.RETRY, null, false, reason);
            }
            case PICKUP_POINT -> new ProblemDecision(StopOutcome.FAILED, PackageStatus.TO_PICKUP_POINT, true, reason);
            case RETURN_TO_FC -> new ProblemDecision(StopOutcome.FAILED, PackageStatus.FAILED, true, reason);
            case REPORT_MISSING -> new ProblemDecision(StopOutcome.FAILED, PackageStatus.MISSING, true, reason);
            case ACCEPTED_WITH_DAMAGE -> new ProblemDecision(StopOutcome.CONTINUE, null, false, reason);
        };
    }

    private void requireContactPolicy(List<ContactAttempt> contacts, DeliveryProblem p) {
        List<String> missing = new ArrayList<>();
        boolean called = false;
        boolean texted = false;
        boolean waited = false;
        for (ContactAttempt c : contacts == null ? List.<ContactAttempt>of() : contacts) {
            if (c.at() == null || c.at().isAfter(p.at())) {
                continue;
            }
            switch (c.kind()) {
                case CALL -> called = true;
                case TEXT -> texted = true;
                case WAIT_STARTED -> waited |= !c.at().plus(wait).isAfter(p.at());
                default -> throw new IllegalStateException("Unexpected contact " + c.kind());
            }
        }
        if (!called) {
            missing.add("call the customer");
        }
        if (!texted) {
            missing.add("text the customer");
        }
        if (!waited) {
            missing.add("wait " + wait.toMinutes() + " minutes");
        }
        if (!missing.isEmpty()) {
            throw new FulfilmentRuleViolation("fulfilment.contact-policy-not-met",
                    "Before choosing a fallback: " + String.join(", ", missing));
        }
    }
}
