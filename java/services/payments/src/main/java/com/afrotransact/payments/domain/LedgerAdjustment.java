package com.afrotransact.payments.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A correcting journal that one person proposes and a different person approves (four eyes). Nothing reaches the
 * ledger until it is approved; the posted journal is immutable like every other, so a mistake is corrected with a new
 * adjustment, never by editing.
 *
 * @param itemId     the reconciliation difference it corrects, or null for a standalone correction
 * @param stripeRef  the Stripe object the journal is booked against: what the next reconciliation matches it to;
 *                   required when a line touches 1000 Stripe clearing
 * @param journalId  the posted journal, once approved
 */
public record LedgerAdjustment(UUID id, UUID itemId, String stripeRef, String currency, String reason,
                               List<JournalLine> lines, AdjustmentStatus status, String proposedBy,
                               Instant proposedAt, String decidedBy, Instant decidedAt, String decisionNote,
                               UUID journalId) {

    static final int MIN_REASON = 15;

    public LedgerAdjustment {
        Objects.requireNonNull(id, "id");
        lines = List.copyOf(lines);
        stripeRef = stripeRef == null || stripeRef.isBlank() ? null : stripeRef.trim();
    }

    public static LedgerAdjustment propose(UUID id, UUID itemId, String stripeRef, String reason,
                                           List<JournalLine> lines, String actor, Instant now) {
        if (reason == null || reason.trim().length() < MIN_REASON) {
            throw new PaymentsRuleViolation("payments.reason-required",
                    "Say why the ledger is wrong (at least " + MIN_REASON + " characters)", Map.of("reason", "Required"));
        }
        if (lines.size() < 2) {
            throw new PaymentsRuleViolation("payments.adjustment-lines", "An adjustment needs at least two lines");
        }
        Map<String, Long> imbalance = Journal.imbalance(lines);
        if (!imbalance.isEmpty()) {
            throw new PaymentsRuleViolation("payments.journal-unbalanced",
                    "Debits and credits must be equal: " + imbalance);
        }
        String currency = lines.get(0).amount().currency();
        if (lines.stream().anyMatch(l -> !l.amount().currency().equals(currency))) {
            throw new PaymentsRuleViolation("payments.adjustment-currency", "An adjustment uses one currency");
        }
        boolean touchesClearing = lines.stream().anyMatch(l -> l.account().chart() == Chart.STRIPE_CLEARING);
        if (touchesClearing && (stripeRef == null || stripeRef.isBlank())) {
            throw new PaymentsRuleViolation("payments.adjustment-stripe-ref",
                    "A line on 1000 Stripe clearing needs the Stripe object it corrects", Map.of("stripeRef", "Required"));
        }
        return new LedgerAdjustment(id, itemId, stripeRef, currency, reason.trim(), lines, AdjustmentStatus.PROPOSED,
                actor, now, null, null, null, null);
    }

    /** The approver is never the proposer. */
    public LedgerAdjustment approve(String approver, String note, UUID postedJournal, Instant now) {
        requireProposed();
        if (approver.equals(proposedBy)) {
            throw new PaymentsForbidden("payments.adjustment-four-eyes",
                    "A different person must approve an adjustment you proposed");
        }
        return new LedgerAdjustment(id, itemId, stripeRef, currency, reason, lines, AdjustmentStatus.POSTED,
                proposedBy, proposedAt, approver, now, blankToNull(note), postedJournal);
    }

    /** Anyone with the approval permission may reject, the proposer included (withdrawing it). */
    public LedgerAdjustment reject(String actor, String note, Instant now) {
        requireProposed();
        if (note == null || note.isBlank()) {
            throw new PaymentsRuleViolation("payments.reason-required", "Say why it is rejected",
                    Map.of("note", "Required"));
        }
        return new LedgerAdjustment(id, itemId, stripeRef, currency, reason, lines, AdjustmentStatus.REJECTED,
                proposedBy, proposedAt, actor, now, note.trim(), null);
    }

    /** The journal this adjustment posts. */
    public Journal toJournal(Instant at) {
        return new Journal(UUID.nameUUIDFromBytes(("adjustment-journal:" + id).getBytes(
                StandardCharsets.UTF_8)), "adjustment:" + id, JournalType.ADJUSTMENT, null, null,
                stripeRef, "Adjustment: " + reason, at, lines);
    }

    /** The id of the journal {@link #toJournal} makes (known before it is posted). */
    public UUID journalIdToPost() {
        return toJournal(Instant.EPOCH).id();
    }

    private void requireProposed() {
        if (status != AdjustmentStatus.PROPOSED) {
            throw new PaymentsRuleViolation("payments.adjustment-decided", "This adjustment was already " + status);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
