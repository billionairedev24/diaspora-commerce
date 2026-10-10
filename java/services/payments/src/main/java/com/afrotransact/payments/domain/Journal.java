package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * An immutable double-entry journal. Debits equal credits in every currency (checked here and again by a deferred
 * constraint trigger in the database).
 *
 * @param key       unique business key ({@code capture:<id>}); posting the same key twice is a no-op
 * @param stripeRef Stripe object behind the movement on 1000 Stripe clearing (charge, refund, transfer, reversal,
 *                  dispute) — used by the daily reconciliation
 */
public record Journal(UUID id, String key, JournalType type, String orderId, String paymentId, String stripeRef,
                      String description, Instant occurredAt, List<JournalLine> lines) {

    public Journal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(occurredAt, "occurredAt");
        lines = List.copyOf(lines);
        if (lines.size() < 2) {
            throw new PaymentsRuleViolation("payments.journal-unbalanced", "A journal needs at least two lines");
        }
        Map<String, Long> imbalance = imbalance(lines);
        if (!imbalance.isEmpty()) {
            throw new PaymentsRuleViolation("payments.journal-unbalanced",
                    "Journal " + key + " does not balance: " + imbalance);
        }
    }

    /** Non-zero debit − credit per currency (empty when balanced). */
    public static Map<String, Long> imbalance(List<JournalLine> lines) {
        Map<String, Long> sums = new TreeMap<>();
        for (JournalLine l : lines) {
            sums.merge(l.amount().currency(), l.signedMinor(), Math::addExact);
        }
        sums.values().removeIf(v -> v == 0L);
        return sums;
    }

    /** Total debits in {@code currency}. */
    public long debitTotal(String currency) {
        return lines.stream().filter(l -> l.side() == EntrySide.DEBIT && l.amount().currency().equals(currency))
                .mapToLong(l -> l.amount().minor()).sum();
    }

    public static Builder builder(JournalType type, String key) {
        return new Builder(type, key);
    }

    /** Collects lines; zero amounts are dropped and negative amounts are posted on the opposite side. */
    public static final class Builder {

        private final JournalType type;
        private final String key;
        private final List<JournalLine> lines = new ArrayList<>();
        private String orderId;
        private String paymentId;
        private String stripeRef;
        private String description;

        private Builder(JournalType type, String key) {
            this.type = type;
            this.key = key;
        }

        public Builder order(String value) {
            this.orderId = value;
            return this;
        }

        public Builder payment(String value) {
            this.paymentId = value;
            return this;
        }

        public Builder stripeRef(String value) {
            this.stripeRef = value;
            return this;
        }

        public Builder description(String value) {
            this.description = value;
            return this;
        }

        public Builder debit(LedgerAccount account, Money amount, String orgId) {
            return add(account, EntrySide.DEBIT, amount, orgId);
        }

        public Builder credit(LedgerAccount account, Money amount, String orgId) {
            return add(account, EntrySide.CREDIT, amount, orgId);
        }

        public Builder debit(LedgerAccount account, Money amount) {
            return debit(account, amount, null);
        }

        public Builder credit(LedgerAccount account, Money amount) {
            return credit(account, amount, null);
        }

        private Builder add(LedgerAccount account, EntrySide side, Money amount, String orgId) {
            if (amount.isZero()) {
                return this;
            }
            if (amount.isNegative()) {
                lines.add(new JournalLine(account, side.opposite(), amount.negate(), orgId));
            } else {
                lines.add(new JournalLine(account, side, amount, orgId));
            }
            return this;
        }

        public boolean isEmpty() {
            return lines.isEmpty();
        }

        public Journal build(UUID id, Instant occurredAt) {
            return new Journal(id, key, type, orderId, paymentId, stripeRef, description, occurredAt, lines);
        }
    }
}
