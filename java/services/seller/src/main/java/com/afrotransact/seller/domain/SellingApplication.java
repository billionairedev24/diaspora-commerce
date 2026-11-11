package com.afrotransact.seller.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A seller's application to sell in one scope (a selling approval case). It follows the same two-step review as an
 * onboarding {@link Application}: a reviewer (step 1), then a different approver (step 2), APPROVED or REJECTED. A
 * "cannot apply" disqualification is a REJECTED application with {@code disqualified} and a reapply date: it cannot
 * be appealed.
 *
 * @param kind   NEW, or RENEWAL when the scope is already approved and a document is being replaced
 * @param source SELLER, or MIGRATION for a food service claim carried over from the old bakery flag
 */
public record SellingApplication(String id, String reference, String orgId, String applicantUserId,
                                 String applicantName, SellingScope scope, String scopeLabel,
                                 UUID ruleId, String kind, String source, ApplicationStatus status,
                                 List<SellingDocument> documents, String note, String reviewerId, Instant reviewedAt,
                                 String finalApproverId, Instant decidedAt, List<String> failedChecks,
                                 List<String> requestedItems, String reasonCode, String decisionNote,
                                 boolean disqualified, LocalDate reapplyAfter, int submissionCount,
                                 Instant submittedAt, Instant createdAt, Instant updatedAt, long version) {

    public SellingApplication {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        documents = documents == null ? List.of() : List.copyOf(documents);
        failedChecks = failedChecks == null ? List.of() : List.copyOf(failedChecks);
        requestedItems = requestedItems == null ? List.of() : List.copyOf(requestedItems);
    }

    public static SellingApplication submit(String id, String reference, String orgId, String applicantUserId,
                                            String applicantName, SellingRule rule, String kind, String source,
                                            List<SellingDocument> documents, String note, Instant now) {
        return new SellingApplication(id, reference, orgId, applicantUserId, applicantName, rule.scope(),
                rule.label(), rule.id(), kind, source, ApplicationStatus.PENDING_REVIEW, documents, note, null, null,
                null, null, List.of(), List.of(), null, null, false, null, 1, now, now, now, 0);
    }

    /** The seller answers a request for information with new documents: back to step 1. */
    public SellingApplication resubmit(List<SellingDocument> docs, String text, Instant now) {
        if (status != ApplicationStatus.INFO_REQUESTED) {
            throw new SellerStateConflict("seller.selling-application-locked",
                    "Application " + reference + " is " + status + " and can't be changed");
        }
        return new SellingApplication(id, reference, orgId, applicantUserId, applicantName, scope, scopeLabel, ruleId,
                kind, source, ApplicationStatus.PENDING_REVIEW, docs, text == null ? note : text, null, null, null,
                null, List.of(), List.of(), null, null, false, null, submissionCount + 1, now, createdAt, now,
                version);
    }

    /** Step 1: approve (to final approval), flag failed checks, or return to the seller for information. */
    public SellingApplication review(ReviewAction action, String reviewer, String reason, List<String> checks,
                                     List<String> items, String message, Instant now) {
        if (!action.isReviewerAction()) {
            throw new SellerRuleViolation("seller.invalid-action", action + " is not a reviewer action");
        }
        boolean returnFlagged = status == ApplicationStatus.FLAGGED && action == ReviewAction.REQUEST_INFO;
        if (status != ApplicationStatus.PENDING_REVIEW && !returnFlagged) {
            throw conflict("needs a decision at step 2 or has been decided");
        }
        notApplicant(reviewer);
        return switch (action) {
            case APPROVE -> moved(ApplicationStatus.AWAITING_FINAL_APPROVAL, reviewer, now, List.of(), List.of(),
                    reason, null);
            case FLAG -> {
                if (checks == null || checks.isEmpty()) {
                    throw new SellerRuleViolation("seller.failed-checks-required",
                            "List the checks that failed when flagging an application");
                }
                yield moved(ApplicationStatus.FLAGGED, reviewer, now, checks, List.of(), reason, null);
            }
            default -> {
                requireItems(items);
                yield moved(ApplicationStatus.INFO_REQUESTED, reviewer, now, List.of(), items, reason, message);
            }
        };
    }

    /** Step 2: a different person approves, sends back, returns to the seller, rejects or disqualifies. */
    public SellingApplication decide(ReviewAction action, String approver, String reason, List<String> items,
                                     String note, LocalDate reapply, Instant now) {
        if (!action.isFinalAction()) {
            throw new SellerRuleViolation("seller.invalid-action", action + " is not a final-approver action");
        }
        if (!status.awaitingFinalDecision()) {
            throw conflict("is not waiting for the final approver");
        }
        notApplicant(approver);
        if (approver.equals(reviewerId)) {
            throw new SellerForbidden("seller.same-person-approval",
                    "You reviewed this application; a different person must give final approval");
        }
        return switch (action) {
            case FINAL_APPROVE -> decided(ApplicationStatus.APPROVED, approver, reason, note, false, null, now);
            case REJECT -> decided(ApplicationStatus.REJECTED, approver, reason, note, false, null, now);
            case DISQUALIFY -> {
                LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
                if (reapply == null || !reapply.isAfter(today)) {
                    throw new SellerRuleViolation("seller.reapply-date-required",
                            "Set the day the seller may apply again (after today)",
                            Map.of("reapplyAfter", "Choose a date after today"));
                }
                yield decided(ApplicationStatus.REJECTED, approver, reason, note, true, reapply, now);
            }
            case REQUEST_INFO -> {
                requireItems(items);
                yield moved(ApplicationStatus.INFO_REQUESTED, approver, now, List.of(), items, reason, note);
            }
            default -> new SellingApplication(id, reference, orgId, applicantUserId, applicantName, scope,
                    scopeLabel, ruleId, kind, source, ApplicationStatus.PENDING_REVIEW, documents, this.note, null,
                    null, null, null, List.of(), List.of(), reason, null, false, null, submissionCount, submittedAt,
                    createdAt, now, version);
        };
    }

    public boolean isApproved() {
        return status == ApplicationStatus.APPROVED;
    }

    /** Waiting for staff (step 1 or step 2). */
    public boolean inReview() {
        return ReviewStage.of(status).isPresent();
    }

    private SellingApplication moved(ApplicationStatus next, String reviewer, Instant now, List<String> checks,
                                     List<String> items, String reason, String message) {
        return new SellingApplication(id, reference, orgId, applicantUserId, applicantName, scope, scopeLabel, ruleId,
                kind, source, next, documents, note, reviewer, now, null, null, checks, items, reason, message, false,
                null, submissionCount, submittedAt, createdAt, now, version);
    }

    private SellingApplication decided(ApplicationStatus next, String approver, String reason, String text,
                                       boolean disqualify, LocalDate reapply, Instant now) {
        return new SellingApplication(id, reference, orgId, applicantUserId, applicantName, scope, scopeLabel, ruleId,
                kind, source, next, documents, note, reviewerId, reviewedAt, approver, now, failedChecks,
                requestedItems, reason, text, disqualify, reapply, submissionCount, submittedAt, createdAt, now,
                version);
    }

    private SellerStateConflict conflict(String what) {
        return new SellerStateConflict("seller.selling-application-state", "Application " + reference + " " + what);
    }

    private static void requireItems(List<String> items) {
        if (items == null || items.isEmpty()) {
            throw new SellerRuleViolation("seller.requested-items-required",
                    "Tick which documents or fields the seller must provide");
        }
    }

    private void notApplicant(String staff) {
        if (staff.equals(applicantUserId)) {
            throw new SellerForbidden("seller.self-review", "You can't review your own application");
        }
    }
}
