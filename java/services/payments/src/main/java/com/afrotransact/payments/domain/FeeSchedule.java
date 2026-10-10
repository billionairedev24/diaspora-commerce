package com.afrotransact.payments.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One immutable version of the fee schedule ("Fees & commissions"). A version applies to orders placed at or after
 * its {@code effectiveFrom} until the next version takes effect; old versions are kept forever.
 *
 * @param categoryBps               commission per category in basis points (keys lower-case)
 * @param defaultCommissionBps      commission for categories that are not listed
 * @param overrides                 negotiated per-seller commission
 * @param cardPctBps                card-processing fee (estimate of Stripe's), percentage part in basis points
 * @param cardFixed                 card-processing fee, fixed part per charge
 * @param fbaPerUnit                FBA pick, pack and ship fee per unit
 * @param reverseCommissionOnRefund refunds give the seller back the matching commission (otherwise retained)
 * @param cancellation              set when a scheduled version was withdrawn before it took effect (the version
 *                                  stays in the history but never applies); null otherwise
 * @param deliveryPricing           AfroTransact Delivery from sellers' stores (S255): customer charge, seller fee,
 *                                  seller overrides and service radius; null when the version does not offer it
 */
public record FeeSchedule(UUID id, int version, Instant effectiveFrom, String currency,
                          Map<String, Integer> categoryBps, int defaultCommissionBps,
                          List<CommissionOverride> overrides, int cardPctBps, Money cardFixed, Money fbaPerUnit,
                          boolean reverseCommissionOnRefund, String note, String createdBy, Instant createdAt,
                          Cancellation cancellation, DeliveryPricing deliveryPricing) {

    /**
     * Withdrawal of a scheduled version: who, when and why (append-only, like the version itself).
     *
     * @param reason why the version was withdrawn (required)
     */
    public record Cancellation(Instant at, String by, String reason) {

        public Cancellation {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(by, "by");
            if (reason == null || reason.isBlank()) {
                throw new PaymentsRuleViolation("payments.invalid-request", "A reason is required",
                        Map.of("reason", "Required"));
            }
            reason = reason.trim();
        }
    }

    /** A version that has not been cancelled and offers no AfroTransact Delivery pricing. */
    public FeeSchedule(UUID id, int version, Instant effectiveFrom, String currency, Map<String, Integer> categoryBps,
                       int defaultCommissionBps, List<CommissionOverride> overrides, int cardPctBps, Money cardFixed,
                       Money fbaPerUnit, boolean reverseCommissionOnRefund, String note, String createdBy,
                       Instant createdAt) {
        this(id, version, effectiveFrom, currency, categoryBps, defaultCommissionBps, overrides, cardPctBps, cardFixed,
                fbaPerUnit, reverseCommissionOnRefund, note, createdBy, createdAt, null, null);
    }

    /** A version without AfroTransact Delivery pricing. */
    public FeeSchedule(UUID id, int version, Instant effectiveFrom, String currency, Map<String, Integer> categoryBps,
                       int defaultCommissionBps, List<CommissionOverride> overrides, int cardPctBps, Money cardFixed,
                       Money fbaPerUnit, boolean reverseCommissionOnRefund, String note, String createdBy,
                       Instant createdAt, Cancellation cancellation) {
        this(id, version, effectiveFrom, currency, categoryBps, defaultCommissionBps, overrides, cardPctBps, cardFixed,
                fbaPerUnit, reverseCommissionOnRefund, note, createdBy, createdAt, cancellation, null);
    }

    public FeeSchedule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(cardFixed, "cardFixed");
        Objects.requireNonNull(fbaPerUnit, "fbaPerUnit");
        currency = Money.zero(currency).currency();
        cardFixed.requireSameCurrency(Money.zero(currency));
        fbaPerUnit.requireSameCurrency(Money.zero(currency));
        Map<String, Integer> normalised = new LinkedHashMap<>();
        categoryBps.forEach((k, v) -> {
            requireBps(v, "category " + k);
            normalised.put(k.trim().toLowerCase(Locale.ROOT), v);
        });
        categoryBps = Map.copyOf(normalised);
        overrides = List.copyOf(overrides);
        requireBps(defaultCommissionBps, "default");
        requireBps(cardPctBps, "card fee");
        if (cardFixed.isNegative() || fbaPerUnit.isNegative()) {
            throw new PaymentsRuleViolation("payments.invalid-fee-schedule", "Fees cannot be negative");
        }
        if (deliveryPricing != null && !deliveryPricing.currency().equals(currency)) {
            throw new PaymentsRuleViolation("payments.currency-mismatch", "Delivery pricing is in "
                    + deliveryPricing.currency() + " but the fee schedule is in " + currency);
        }
    }

    /**
     * Prices one AfroTransact Delivery trip from {@code sellerOrgId}'s pickup location (S255): the customer charge and
     * the seller fee (seller override first; none for AfroTransact Retail). Unavailable (NOT_CONFIGURED) when this
     * version has no delivery pricing, or (BEYOND_MAX_RADIUS) beyond the service radius.
     */
    public DeliveryQuote quoteDelivery(String sellerOrgId, BigDecimal miles, int packages, String retailOrgId) {
        if (miles == null || miles.signum() < 0) {
            throw new PaymentsRuleViolation("payments.invalid-request", "distanceMiles must be ≥ 0",
                    Map.of("distanceMiles", "Must be ≥ 0"));
        }
        if (packages < 1) {
            throw new PaymentsRuleViolation("payments.invalid-request", "packages must be ≥ 1",
                    Map.of("packages", "At least 1"));
        }
        if (deliveryPricing == null) {
            Money zero = Money.zero(currency);
            return new DeliveryQuote(false, DeliveryQuote.NOT_CONFIGURED, id, version, currency, miles, packages, null,
                    zero, zero, sellerOrgId != null && sellerOrgId.equals(retailOrgId) ? "RETAIL" : "DEFAULT");
        }
        return deliveryPricing.quote(this, sellerOrgId, miles, packages, retailOrgId);
    }

    static void requireBps(int bps, String what) {
        if (bps < 0 || bps > 10_000) {
            throw new PaymentsRuleViolation("payments.invalid-fee-schedule",
                    "Commission / fee rate for " + what + " must be between 0 and 10000 bps");
        }
    }

    /** Seller override for the category, then the seller's all-category override, then the category, then default. */
    public int commissionBps(String orgId, String category) {
        String cat = category == null ? "" : category.trim().toLowerCase(Locale.ROOT);
        Optional<CommissionOverride> exact = overrides.stream()
                .filter(o -> o.orgId().equals(orgId) && o.category().equals(cat)).findFirst();
        if (exact.isPresent()) {
            return exact.get().commissionBps();
        }
        Optional<CommissionOverride> any = overrides.stream()
                .filter(o -> o.orgId().equals(orgId) && o.category().equals(CommissionOverride.ANY)).findFirst();
        if (any.isPresent()) {
            return any.get().commissionBps();
        }
        return categoryBps.getOrDefault(cat, defaultCommissionBps);
    }

    /**
     * Fees for {@code orgId} selling in {@code category}. AfroTransact Retail ({@code retailOrgId}) is not a
     * marketplace seller: zero commission (whatever the overrides say) and zero FBA fee, source {@code RETAIL}; only
     * the card-fee estimate (its Stripe-fee share) applies.
     */
    public EffectiveFees effectiveFor(String orgId, String category, String retailOrgId) {
        String cat = category == null ? "" : category.trim().toLowerCase(Locale.ROOT);
        boolean retail = orgId != null && orgId.equals(retailOrgId);
        String source;
        if (retail) {
            source = "RETAIL";
        } else if (orgId != null && overrides.stream().anyMatch(o -> o.orgId().equals(orgId)
                && (o.category().equals(cat) || o.category().equals(CommissionOverride.ANY)))) {
            source = "OVERRIDE";
        } else {
            source = categoryBps.containsKey(cat) ? "CATEGORY" : "DEFAULT";
        }
        int bps = retail ? 0 : commissionBps(orgId == null ? "" : orgId, cat);
        return new EffectiveFees(id, version, effectiveFrom, currency, cat, orgId, bps, source, cardPctBps, cardFixed,
                retail ? Money.zero(currency) : fbaPerUnit, reverseCommissionOnRefund, retail);
    }

    /** Card fee estimate (percentage + fixed) used before Stripe reports the real fee of a charge. */
    public Money estimateCardFee(Money charge) {
        if (!charge.isPositive()) {
            return Money.zero(charge.currency());
        }
        return charge.basisPoints(cardPctBps).plus(cardFixed);
    }

    public boolean isCancelled() {
        return cancellation != null;
    }

    /**
     * Withdraws this scheduled version. Only a version that has not taken effect yet can be cancelled, so no order
     * was ever placed under it; the row is kept (marked cancelled) and never rewritten.
     */
    public FeeSchedule cancel(Instant now, String by, String reason) {
        if (isCancelled()) {
            throw new PaymentsStateConflict("payments.fee-schedule-cancelled",
                    "Version " + version + " was already cancelled");
        }
        if (!effectiveFrom.isAfter(now)) {
            throw new PaymentsStateConflict("payments.fee-schedule-in-force",
                    "Version " + version + " took effect at " + effectiveFrom + " and can no longer be cancelled");
        }
        return new FeeSchedule(id, version, effectiveFrom, currency, categoryBps, defaultCommissionBps, overrides,
                cardPctBps, cardFixed, fbaPerUnit, reverseCommissionOnRefund, note, createdBy, createdAt,
                new Cancellation(now, by, reason), deliveryPricing);
    }

    /**
     * The version in force at {@code at}: the latest effectiveFrom ≤ at (highest version on ties); cancelled
     * versions never apply.
     */
    public static Optional<FeeSchedule> activeAt(List<FeeSchedule> history, Instant at) {
        return history.stream().filter(s -> !s.isCancelled() && !s.effectiveFrom().isAfter(at))
                .max(Comparator.comparing(FeeSchedule::effectiveFrom).thenComparingInt(FeeSchedule::version));
    }

    /** The newest version that is not cancelled (what a new version must follow). */
    public static Optional<FeeSchedule> latestLive(List<FeeSchedule> history) {
        return history.stream().filter(s -> !s.isCancelled()).max(Comparator.comparingInt(FeeSchedule::version));
    }

    /** The next version number (cancelled versions keep theirs). */
    public static int nextVersion(List<FeeSchedule> history) {
        return history.stream().mapToInt(FeeSchedule::version).max().orElse(0) + 1;
    }

    /**
     * A new version never rewrites the past: it takes effect now or later, and not before the latest version, so
     * orders already placed keep the schedule they were placed under.
     */
    public static void requireValidSuccessor(Instant effectiveFrom, Optional<FeeSchedule> latest, Instant now) {
        if (effectiveFrom.isBefore(now)) {
            throw new PaymentsRuleViolation("payments.fee-schedule-backdated",
                    "A fee schedule version cannot take effect in the past (" + effectiveFrom + ")",
                    Map.of("effectiveFrom", "Must be now or later"));
        }
        if (latest.isPresent() && effectiveFrom.isBefore(latest.get().effectiveFrom())) {
            throw new PaymentsRuleViolation("payments.fee-schedule-out-of-order",
                    "Version " + latest.get().version() + " already takes effect at " + latest.get().effectiveFrom(),
                    Map.of("effectiveFrom", "Must not be before the latest version"));
        }
    }
}
