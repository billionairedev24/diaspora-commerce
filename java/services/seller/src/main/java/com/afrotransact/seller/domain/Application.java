package com.afrotransact.seller.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Onboarding application (one per organisation) and its two-step review state machine.
 *
 * <pre>
 * DRAFT ──submit──▶ PENDING_REVIEW ──approve──▶ AWAITING_FINAL_APPROVAL ──final approve──▶ APPROVED
 *   ▲                 │  │                         │   ▲ (FLAGGED too)       └──reject──▶ REJECTED
 *   │                 │  └──flag──▶ FLAGGED ───────┘   └──send back──▶ PENDING_REVIEW
 *   └─ resubmit ◀─ INFO_REQUESTED ◀──request info (step 1, or step 2 from FLAGGED / AWAITING_FINAL_APPROVAL)
 * </pre>
 * The final approver must be a different person than the reviewer, and nobody reviews their own application.
 */
public record Application(String id, String reference, String orgId, String applicantUserId, OrgKind kind,
                          ApplicationStatus status, OnboardingStep currentStep, ApplicationData data,
                          Set<String> prefilledFields, String reviewerId, Instant reviewedAt,
                          String finalApproverId, Instant decidedAt, List<String> failedChecks,
                          List<String> requestedItems, String reasonCode, int submissionCount,
                          Instant submittedAt, Instant createdAt, Instant updatedAt, long version) {

    public Application {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        data = data == null ? ApplicationData.empty() : data;
        prefilledFields = prefilledFields == null ? Set.of() : Set.copyOf(prefilledFields);
        failedChecks = failedChecks == null ? List.of() : List.copyOf(failedChecks);
        requestedItems = requestedItems == null ? List.of() : List.copyOf(requestedItems);
    }

    public static Application start(String id, String reference, String orgId, String applicantUserId, OrgKind kind,
                                    ApplicationData prefill, Set<String> prefilledFields, Instant now) {
        return new Application(id, reference, orgId, applicantUserId, kind, ApplicationStatus.DRAFT,
                OnboardingStep.BUSINESS, prefill, prefilledFields, null, null, null, null, List.of(), List.of(),
                null, 0, null, now, now, 0);
    }

    /** Autosave of a step by the applicant (only while DRAFT or INFO_REQUESTED). */
    public Application edit(ApplicationData next, OnboardingStep step, Set<String> prefilled, Instant now) {
        requireEditable();
        return new Application(id, reference, orgId, applicantUserId, kind, status, step, next, prefilled,
                reviewerId, reviewedAt, finalApproverId, decidedAt, failedChecks, requestedItems, reasonCode,
                submissionCount, submittedAt, createdAt, now, version);
    }

    /** The business changes how its public page looks; allowed in any status (it is not part of the review). */
    public Application withBranding(BusinessSection.Branding branding, Instant now) {
        BusinessSection b = data.business();
        BusinessSection next = b == null ? new BusinessSection(null, null, null, null, null, null, null, branding)
                : new BusinessSection(b.offering(), b.entityType(), b.country(), b.storeName(), b.contactEmail(),
                        b.phone(), b.website(), branding);
        return new Application(id, reference, orgId, applicantUserId, kind, status, currentStep,
                data.withBusiness(next), prefilledFields, reviewerId, reviewedAt, finalApproverId, decidedAt,
                failedChecks, requestedItems, reasonCode, submissionCount, submittedAt, createdAt, now, version);
    }

    /** Stripe verification results are refreshed by the system at any time before approval. */
    public Application withStripe(StripeVerification stripe, Instant now) {
        return new Application(id, reference, orgId, applicantUserId, kind, status, currentStep,
                data.withStripe(stripe), prefilledFields, reviewerId, reviewedAt, finalApproverId, decidedAt,
                failedChecks, requestedItems, reasonCode, submissionCount, submittedAt, createdAt, now, version);
    }

    /** Stripe's verification results and the payout account it holds, mirrored from the connected account. */
    public Application withStripeAccount(StripeVerification stripe, PayoutSection payouts, Instant now) {
        return new Application(id, reference, orgId, applicantUserId, kind, status, currentStep,
                data.withStripe(stripe).withPayouts(payouts), prefilledFields, reviewerId, reviewedAt,
                finalApproverId, decidedAt, failedChecks, requestedItems, reasonCode, submissionCount, submittedAt,
                createdAt, now, version);
    }

    public Application submit(Instant now) {
        requireEditable();
        return new Application(id, reference, orgId, applicantUserId, kind, ApplicationStatus.PENDING_REVIEW,
                OnboardingStep.REVIEW, data, prefilledFields, null, null, null, null, List.of(), List.of(), null,
                submissionCount + 1, now, createdAt, now, version);
    }

    /** Step 1: the reviewer approves (→ awaiting final approval), flags failed checks or requests info. */
    public Application review(ReviewAction action, String reviewer, String reason, List<String> checks,
                              List<String> items, Instant now) {
        if (!action.isReviewerAction()) {
            throw new SellerRuleViolation("seller.invalid-action", action + " is not a reviewer action");
        }
        // A flagged application can still be returned to the applicant by any reviewer (including whoever flagged it):
        // asking for a fix is not an approval, so the two-person rule does not apply to it.
        boolean returnFlagged = status == ApplicationStatus.FLAGGED && action == ReviewAction.REQUEST_INFO;
        if (status != ApplicationStatus.PENDING_REVIEW && !returnFlagged) {
            throw notReviewable(reviewer);
        }
        notApplicant(reviewer);
        return switch (action) {
            case APPROVE -> moved(ApplicationStatus.AWAITING_FINAL_APPROVAL, reviewer, now, List.of(), List.of(),
                    reason);
            case FLAG -> {
                if (checks == null || checks.isEmpty()) {
                    throw new SellerRuleViolation("seller.failed-checks-required",
                            "List the checks that failed when flagging an application");
                }
                yield moved(ApplicationStatus.FLAGGED, reviewer, now, checks, List.of(), reason);
            }
            default -> {
                if (items == null || items.isEmpty()) {
                    throw new SellerRuleViolation("seller.requested-items-required",
                            "Tick which documents or fields the applicant must provide");
                }
                yield moved(ApplicationStatus.INFO_REQUESTED, reviewer, now, List.of(), items, reason);
            }
        };
    }

    /** Step 2 without a request for information. */
    public Application decide(ReviewAction action, String approver, String reason, Instant now) {
        return decide(action, approver, reason, List.of(), now);
    }

    /**
     * Step 2: a different person gives final approval, sends it back to review, rejects it, or returns it to the
     * applicant to fix (REQUEST_INFO with what is missing), which is how a flagged application reaches the applicant.
     */
    public Application decide(ReviewAction action, String approver, String reason, List<String> items, Instant now) {
        if (!action.isFinalAction()) {
            throw new SellerRuleViolation("seller.invalid-action", action + " is not a final-approver action");
        }
        if (!status.awaitingFinalDecision()) {
            throw notDecidable(approver);
        }
        notApplicant(approver);
        if (approver.equals(reviewerId)) {
            throw new SellerForbidden("seller.same-person-approval",
                    "You reviewed this application; a different person must give final approval");
        }
        return switch (action) {
            case FINAL_APPROVE -> decided(ApplicationStatus.APPROVED, approver, reason, now);
            case REJECT -> decided(ApplicationStatus.REJECTED, approver, reason, now);
            case REQUEST_INFO -> {
                if (items == null || items.isEmpty()) {
                    throw new SellerRuleViolation("seller.requested-items-required",
                            "Tick which documents or fields the applicant must provide");
                }
                yield moved(ApplicationStatus.INFO_REQUESTED, approver, now, List.of(), items, reason);
            }
            default -> new Application(id, reference, orgId, applicantUserId, kind, ApplicationStatus.PENDING_REVIEW,
                    currentStep, data, prefilledFields, null, null, null, null, List.of(), List.of(), reason,
                    submissionCount, submittedAt, createdAt, now, version);
        };
    }

    public boolean isApproved() {
        return status == ApplicationStatus.APPROVED;
    }

    /**
     * Whether {@code action} by {@code actor} is a repeat of the decision that put the application in its current
     * state ({@code last} is the latest timeline entry): a double submit or a retried request, which changes nothing.
     */
    public boolean isRepeatOf(ReviewAction action, String actor, TimelineEntry last) {
        if (action == null || actor == null || last == null || !last.decided(action, actor, status)) {
            return false;
        }
        return switch (action) {
            case FINAL_APPROVE, REJECT -> actor.equals(finalApproverId);
            case APPROVE, FLAG, REQUEST_INFO -> actor.equals(reviewerId);
            case SEND_BACK -> status == ApplicationStatus.PENDING_REVIEW;
            default -> false;
        };
    }

    /** Step 1 is not possible now: says why, naming an earlier decision when there was one. */
    private SellerStateConflict notReviewable(String actor) {
        return switch (status) {
            case APPROVED, REJECTED -> ApplicationAlreadyDecided.of(status, finalApproverId, decidedAt, actor);
            case AWAITING_FINAL_APPROVAL, FLAGGED -> ApplicationAlreadyDecided.of(status, reviewerId, reviewedAt,
                    actor);
            case INFO_REQUESTED -> new SellerStateConflict("seller.not-pending-review", "Application " + reference
                    + " is waiting for the applicant to send the information requested");
            default -> new SellerStateConflict("seller.not-pending-review",
                    "Application " + reference + " hasn't been submitted yet");
        };
    }

    /** Step 2 is not possible now: says why, naming the final decision when there was one. */
    private SellerStateConflict notDecidable(String actor) {
        return switch (status) {
            case APPROVED, REJECTED -> ApplicationAlreadyDecided.of(status, finalApproverId, decidedAt, actor);
            case PENDING_REVIEW -> new SellerStateConflict("seller.not-awaiting-final-approval",
                    "Application " + reference + " needs a reviewer decision (step 1) first");
            case INFO_REQUESTED -> new SellerStateConflict("seller.not-awaiting-final-approval", "Application "
                    + reference + " is waiting for the applicant to send the information requested");
            default -> new SellerStateConflict("seller.not-awaiting-final-approval",
                    "Application " + reference + " hasn't been submitted yet");
        };
    }

    private Application moved(ApplicationStatus next, String reviewer, Instant now, List<String> checks,
                              List<String> items, String reason) {
        return new Application(id, reference, orgId, applicantUserId, kind, next, currentStep, data, prefilledFields,
                reviewer, now, null, null, checks, items, reason, submissionCount, submittedAt, createdAt, now,
                version);
    }

    private Application decided(ApplicationStatus next, String approver, String reason, Instant now) {
        return new Application(id, reference, orgId, applicantUserId, kind, next, currentStep, data, prefilledFields,
                reviewerId, reviewedAt, approver, now, failedChecks, requestedItems, reason, submissionCount,
                submittedAt, createdAt, now, version);
    }

    private void requireEditable() {
        if (!status.editableByApplicant()) {
            throw new SellerStateConflict("seller.application-locked",
                    "Application " + reference + " is " + status + " and can't be edited");
        }
    }

    private void notApplicant(String staff) {
        if (staff.equals(applicantUserId)) {
            throw new SellerForbidden("seller.self-review", "You can't review your own application");
        }
    }
}
