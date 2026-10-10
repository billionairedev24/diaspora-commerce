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
 *   └─ resubmit ◀─ INFO_REQUESTED ◀──request info──┘
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

    /** Stripe verification results are refreshed by the system at any time before approval. */
    public Application withStripe(StripeVerification stripe, Instant now) {
        return new Application(id, reference, orgId, applicantUserId, kind, status, currentStep,
                data.withStripe(stripe), prefilledFields, reviewerId, reviewedAt, finalApproverId, decidedAt,
                failedChecks, requestedItems, reasonCode, submissionCount, submittedAt, createdAt, now, version);
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
        if (status != ApplicationStatus.PENDING_REVIEW) {
            throw new SellerStateConflict("seller.not-pending-review",
                    "Application " + reference + " is " + status + ", not PENDING_REVIEW");
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

    /** Step 2: a different person gives final approval, sends it back to review, or rejects it. */
    public Application decide(ReviewAction action, String approver, String reason, Instant now) {
        if (!action.isFinalAction()) {
            throw new SellerRuleViolation("seller.invalid-action", action + " is not a final-approver action");
        }
        if (!status.awaitingFinalDecision()) {
            throw new SellerStateConflict("seller.not-awaiting-final-approval",
                    "Application " + reference + " is " + status + "; it needs a reviewer decision first");
        }
        notApplicant(approver);
        if (approver.equals(reviewerId)) {
            throw new SellerForbidden("seller.same-person-approval",
                    "You reviewed this application; a different person must give final approval");
        }
        return switch (action) {
            case FINAL_APPROVE -> decided(ApplicationStatus.APPROVED, approver, reason, now);
            case REJECT -> decided(ApplicationStatus.REJECTED, approver, reason, now);
            default -> new Application(id, reference, orgId, applicantUserId, kind, ApplicationStatus.PENDING_REVIEW,
                    currentStep, data, prefilledFields, null, null, null, null, List.of(), List.of(), reason,
                    submissionCount, submittedAt, createdAt, now, version);
        };
    }

    public boolean isApproved() {
        return status == ApplicationStatus.APPROVED;
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
