package com.afrotransact.seller.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.afrotransact.seller.domain.DocumentAccess;
import com.afrotransact.seller.domain.SellingApplication;
import com.afrotransact.seller.domain.SellingApproval;
import com.afrotransact.seller.domain.SellingDocument;
import com.afrotransact.seller.domain.SellingDocumentRequirement;
import com.afrotransact.seller.domain.SellingRule;
import com.afrotransact.seller.domain.TimelineEntry;

/** Read models of selling approvals (records only; the services build them). */
public final class SellingViews {

    private SellingViews() {
    }

    /** A document slot of a rule: what is asked, with the sentence the seller reads. */
    public record Slot(String kind, String label, boolean optional, boolean expiryRequired, boolean dated,
                       boolean namesAfroTransact) {

        static Slot of(SellingDocumentRequirement r) {
            return new Slot(r.kind().name(), r.kind().label(), r.optional(), r.kind().expiryRequired(),
                    r.kind().dated(), r.kind().namesAfroTransact());
        }
    }

    /** A document on file. */
    public record OnFile(String id, String kind, String label, String assetId, String fileName, LocalDate issuedOn,
                         LocalDate expiresOn, boolean namesAfroTransact, String note) {

        static OnFile of(SellingDocument d) {
            return new OnFile(d.id(), d.kind().name(), d.kind().label(), d.assetId(), d.fileName(), d.issuedOn(),
                    d.expiresOn(), d.namesAfroTransact(), d.note());
        }
    }

    /**
     * One line of the Selling approvals page: a scope that needs approval (or is not available), with the seller's
     * state for it in plain words.
     *
     * @param level          APPLY or NOT_AVAILABLE
     * @param state          NOT_APPLIED, APPLIED, NEEDS_ATTENTION, APPROVED, EXPIRED or NOT_AVAILABLE
     * @param daysToExpiry   whole days until the earliest document expires (negative once expired)
     * @param applicationStatus the seller's latest application's status (INFO_REQUESTED: update it)
     */
    public record ApprovalRow(String scopeType, String scopeKey, String label, String level, String state,
                              String statusWords, String message, boolean canApply, Instant approvedAt,
                              Instant expiresAt, Long daysToExpiry, LocalDate reapplyAfter, List<Slot> documents,
                              List<OnFile> onFile, String applicationId, String applicationStatus, String note,
                              boolean listingsPaused) {

        public ApprovalRow {
            documents = documents == null ? List.of() : List.copyOf(documents);
            onFile = onFile == null ? List.of() : List.copyOf(onFile);
        }
    }

    /** A notice for the seller: an expiry coming up, listings paused, an application waiting for them. */
    public record Notice(String severity, String scopeKey, String scopeLabel, String message) {
    }

    /** What the Selling approvals page shows. */
    public record SellingApprovals(List<ApprovalRow> approvals, List<Notice> notices, Capabilities capabilities) {

        public SellingApprovals {
            approvals = List.copyOf(approvals);
            notices = List.copyOf(notices);
        }
    }

    /** Seller capabilities (D11): food service is granted by approval of a permit with an expiry. */
    public record Capabilities(Capability foodService) {
    }

    /** @param state NOT_APPLIED, APPLIED, NEEDS_ATTENTION, APPROVED, EXPIRED or NOT_AVAILABLE; approved only when it may be used now */
    public record Capability(boolean approved, String state, Instant expiresAt) {
    }

    /** A selling application as the seller sees it. */
    public record MyApplication(String id, String reference, String scopeType, String scopeKey, String scopeLabel,
                                String kind, String status, String statusWords, String message, List<String> requestedItems,
                                boolean disqualified, LocalDate reapplyAfter, List<OnFile> documents,
                                List<Slot> required, Instant submittedAt, Instant updatedAt) {
    }

    /** A queue row for staff. */
    public record Summary(String id, String reference, String orgId, String applicantName, String scopeType,
                          String scopeKey, String scopeLabel, String kind, String source, String status,
                          int reviewStep, String reviewerId, List<String> failedChecks, Instant submittedAt,
                          Instant updatedAt, ApplicationCaseView workflow) {

        public Summary {
            failedChecks = List.copyOf(failedChecks);
        }
    }

    /** The full case for staff. */
    public record CaseView(String id, String reference, String orgId, String applicantName, String scopeType,
                           String scopeKey, String scopeLabel, String kind, String source, String status, String note,
                           List<OnFile> documents, List<Slot> required, String reviewerId, Instant reviewedAt,
                           String finalApproverId, Instant decidedAt, List<String> failedChecks,
                           List<String> requestedItems, String reasonCode, String decisionNote, boolean disqualified,
                           LocalDate reapplyAfter, int submissionCount, Instant submittedAt, Instant updatedAt,
                           List<TimelineEntry> timeline, List<DocumentAccess> accessLog, ApplicationCaseView workflow,
                           ApprovalView approval) {

        public CaseView {
            failedChecks = List.copyOf(failedChecks);
            requestedItems = List.copyOf(requestedItems);
            timeline = List.copyOf(timeline);
            accessLog = List.copyOf(accessLog);
        }
    }

    /** A granted (or not) approval, for staff. */
    public record ApprovalView(UUID id, String orgId, String scopeType, String scopeKey, String label, String status,
                               String reason, LocalDate reapplyAfter, Instant approvedAt, Instant expiresAt,
                               Long daysToExpiry, boolean paused, String pauseReason, boolean listingsPaused,
                               String applicationId, List<OnFile> documents, Instant updatedAt) {

        static ApprovalView of(SellingApproval a, Instant now) {
            return new ApprovalView(a.id(), a.orgId(), a.scope().type().name(), a.scope().key(), a.label(),
                    a.status().name(), a.reason(), a.reapplyAfter(), a.approvedAt(), a.expiresAt(),
                    a.daysToExpiry(now), a.paused(), a.pauseReason(), a.listingsPaused(), a.applicationId(),
                    a.documents().stream().map(OnFile::of).toList(), a.updatedAt());
        }
    }

    /** A rule for staff. */
    public record RuleView(UUID id, String scopeType, String scopeKey, String label, String level,
                           List<Slot> documents, String reason, String note, String approvalLevel, String capability,
                           List<String> materials, boolean watch, int reapplyAfterDays, boolean active, String source,
                           Instant updatedAt, String updatedByName, long version) {

        static RuleView of(SellingRule r) {
            return new RuleView(r.id(), r.scope().type().name(), r.scope().key(), r.label(), r.level().name(),
                    r.documents().stream().map(Slot::of).toList(), r.reason(), r.note(), r.approvalLevel(),
                    r.capability(), r.materials().stream().sorted().toList(), r.watch(), r.reapplyAfterDays(),
                    r.active(), r.source(), r.updatedAt(), r.updatedByName(), r.version());
        }
    }
}
