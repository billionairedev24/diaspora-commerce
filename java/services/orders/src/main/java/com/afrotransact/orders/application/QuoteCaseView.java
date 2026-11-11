package com.afrotransact.orders.application;

import java.time.Instant;
import java.util.List;

import com.afrotransact.orders.domain.QuoteSnapshot;

/**
 * A delivery quote case as staff see it: the request, the answer, who has it and the timeline, all with names. The
 * {@code can*} flags are what the caller may do now (permission and who holds the case), so a console never re-derives
 * the rules.
 */
public record QuoteCaseView(String id, String reference, String status, String statusLabel, String customerName,
                            String place, String currency, List<QuoteSnapshot.Pack> packages,
                            List<QuoteSnapshot.Line> lines, int itemCount, String contact, String note,
                            Long amountMinor, String agreedReason, String agreedByName, Instant agreedAt,
                            Instant expiresAt, String declinedReason, String declinedByName, Instant declinedAt,
                            boolean usedOnOrder, Instant usedAt, Instant createdAt, Workflow workflow,
                            List<Entry> timeline) {

    /**
     * Who has the case and how long it has waited. {@code stage} is QUOTE while it waits for staff, else null (with
     * the shopper, or closed).
     */
    public record Workflow(String stage, String holder, String assigneeId, String assigneeName, Instant assignedAt,
                           String assignedByName, Instant stageEnteredAt, long waitingSeconds, String slaState,
                           Instant slaDueAt, int escalationLevel, Instant escalatedAt, String escalationReason,
                           List<String> escalatedTo, boolean mine, boolean canClaim, boolean canAssign,
                           boolean canRelease, boolean canEscalate, boolean canDecide) {
    }

    /** One timeline row. */
    public record Entry(String action, String actorName, String note, Long amountMinor, String fromStatus,
                        String toStatus, Instant at) {
    }

    /** One page of the staff queue. */
    public record Page(List<QuoteCaseView> items, int page, int size, long total) {
    }
}
