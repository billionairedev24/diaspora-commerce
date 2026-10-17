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
 * @param planBps                   product commission per plan: plan code → category → bps ({@code *} = the plan's
 *                                  rate for unlisted categories); between negotiated rates and category rates
 * @param connectionFee             the service connection-fee section (default, per category, per plan); null for
 *                                  versions created before it existed (service lines then pay the {@code services}
 *                                  commission rate, as before)
 */
public record FeeSchedule(UUID id, int version, Instant effectiveFrom, String currency,
                          Map<String, Integer> categoryBps, int defaultCommissionBps,
                          List<CommissionOverride> overrides, int cardPctBps, Money cardFixed, Money fbaPerUnit,
                          boolean reverseCommissionOnRefund, String note, String createdBy, Instant createdAt,
                          Cancellation cancellation, DeliveryPricing deliveryPricing,
                          Map<String, Map<String, Integer>> planBps, RateTable connectionFee,
                          Map<String, Long> planFbaMinor) {

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

    /** A version without plan rates and without a connection-fee section. */
    public FeeSchedule(UUID id, int version, Instant effectiveFrom, String currency, Map<String, Integer> categoryBps,
                       int defaultCommissionBps, List<CommissionOverride> overrides, int cardPctBps, Money cardFixed,
                       Money fbaPerUnit, boolean reverseCommissionOnRefund, String note, String createdBy,
                       Instant createdAt, Cancellation cancellation, DeliveryPricing deliveryPricing) {
        this(id, version, effectiveFrom, currency, categoryBps, defaultCommissionBps, overrides, cardPctBps, cardFixed,
                fbaPerUnit, reverseCommissionOnRefund, note, createdBy, createdAt, cancellation, deliveryPricing,
                Map.of(), null);
    }

    /** A version whose plans all pay the default FBA fee. */
    public FeeSchedule(UUID id, int version, Instant effectiveFrom, String currency, Map<String, Integer> categoryBps,
                       int defaultCommissionBps, List<CommissionOverride> overrides, int cardPctBps, Money cardFixed,
                       Money fbaPerUnit, boolean reverseCommissionOnRefund, String note, String createdBy,
                       Instant createdAt, Cancellation cancellation, DeliveryPricing deliveryPricing,
                       Map<String, Map<String, Integer>> planBps, RateTable connectionFee) {
        this(id, version, effectiveFrom, currency, categoryBps, defaultCommissionBps, overrides, cardPctBps, cardFixed,
                fbaPerUnit, reverseCommissionOnRefund, note, createdBy, createdAt, cancellation, deliveryPricing,
                planBps, connectionFee, Map.of());
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
        planBps = new RateTable(defaultCommissionBps, Map.of(), planBps).planBps();
        Map<String, Long> fbaByPlan = new LinkedHashMap<>();
        if (planFbaMinor != null) {
            planFbaMinor.forEach((plan, minor) -> {
                if (plan == null || plan.isBlank() || minor == null || minor < 0) {
                    throw new PaymentsRuleViolation("payments.invalid-fee-schedule",
                            "Plan FBA fees need a plan and a fee of zero or more");
                }
                fbaByPlan.put(plan.trim().toLowerCase(Locale.ROOT), minor);
            });
        }
        planFbaMinor = Map.copyOf(fbaByPlan);
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
     * The rate section of {@code kind}: product commission (default, categories, plans), or the connection-fee
     * section. A version without a connection-fee section prices services like before it existed: the commission
     * rate of the {@code services} category (or the default), with no plan rates.
     */
    public RateTable section(FeeKind kind) {
        if (kind == FeeKind.SERVICE) {
            if (connectionFee != null) {
                return connectionFee;
            }
            return new RateTable(categoryBps.getOrDefault(FeeKind.LEGACY_SERVICES_CATEGORY, defaultCommissionBps),
                    Map.of(), Map.of());
        }
        return new RateTable(defaultCommissionBps, categoryBps, planBps);
    }

    /**
     * The rate {@code orgId} pays on a {@code kind} line of {@code category} at {@code at}, first match wins:
     * <ol>
     *   <li>a negotiated rate of the organisation active at {@code at} (an entry of {@code negotiated}, or for
     *       products a commission override of this version): the category's before the {@code *} one;</li>
     *   <li>the organisation's plan rate ({@code planCode}) for the category, else the plan's {@code *} rate;</li>
     *   <li>the section's category rate;</li>
     *   <li>the section's default rate.</li>
     * </ol>
     * AfroTransact Retail ({@code retailOrgId}) always pays nothing (source RETAIL), whatever is negotiated.
     */
    public AppliedRate rateFor(FeeKind kind, String orgId, String category, String planCode,
                               List<NegotiatedRate> negotiated, Instant at, String retailOrgId) {
        String cat = category == null ? "" : category.trim().toLowerCase(Locale.ROOT);
        String plan = planCode == null || planCode.isBlank() ? null : planCode.trim().toLowerCase(Locale.ROOT);
        if (orgId != null && orgId.equals(retailOrgId)) {
            return AppliedRate.retail(kind, cat);
        }
        if (orgId != null) {
            NegotiatedRateType type = NegotiatedRateType.of(kind);
            List<NegotiatedRate> live = negotiated == null ? List.of() : negotiated.stream()
                    .filter(n -> n.orgId().equals(orgId) && n.type() == type && n.activeAt(at)).toList();
            for (String key : cat.isEmpty() ? List.of(CommissionOverride.ANY) : List.of(cat, CommissionOverride.ANY)) {
                Optional<NegotiatedRate> n = live.stream().filter(r -> r.category().equals(key))
                        .max(Comparator.comparing(NegotiatedRate::startsAt));
                if (n.isPresent()) {
                    return new AppliedRate(kind, n.get().rateBps(), RateSource.OVERRIDE, cat, plan, n.get().id(),
                            n.get().endsAt());
                }
                if (kind == FeeKind.PRODUCT) {
                    Optional<CommissionOverride> o = overrides.stream()
                            .filter(x -> x.orgId().equals(orgId) && x.category().equals(key)).findFirst();
                    if (o.isPresent()) {
                        return new AppliedRate(kind, o.get().commissionBps(), RateSource.OVERRIDE, cat, plan, null,
                                null);
                    }
                }
            }
        }
        RateTable table = section(kind);
        Optional<Integer> planRate = table.planRate(plan, cat);
        if (planRate.isPresent()) {
            return new AppliedRate(kind, planRate.get(), RateSource.PLAN, cat, plan, null, null);
        }
        Integer catRate = cat.isEmpty() ? null : table.categoryBps().get(cat);
        if (catRate != null) {
            return new AppliedRate(kind, catRate, RateSource.CATEGORY, cat, plan, null, null);
        }
        return new AppliedRate(kind, table.defaultBps(), RateSource.DEFAULT, cat, plan, null, null);
    }

    /**
     * Product fees for {@code orgId} selling in {@code category}, without plan or negotiated rates (only this
     * version's commission overrides). AfroTransact Retail ({@code retailOrgId}) is not a marketplace seller: zero
     * commission (whatever the overrides say) and zero FBA fee, source {@code RETAIL}; only the card-fee estimate (its
     * Stripe-fee share) applies.
     */
    public EffectiveFees effectiveFor(String orgId, String category, String retailOrgId) {
        return effectiveFor(FeeKind.PRODUCT, orgId, category, null, List.of(), effectiveFrom, retailOrgId);
    }

    /** The FBA fee per unit of {@code planCode}: its own when the version sets one, else the default. */
    public Money fbaPerUnitFor(String planCode) {
        if (planCode == null || planCode.isBlank()) {
            return fbaPerUnit;
        }
        Long minor = planFbaMinor.get(planCode.trim().toLowerCase(Locale.ROOT));
        return minor == null ? fbaPerUnit : Money.of(minor, currency);
    }

    /** The fees of one organisation, kind and category at {@code at}: {@link #rateFor} plus card and FBA fees. */
    public EffectiveFees effectiveFor(FeeKind kind, String orgId, String category, String planCode,
                                      List<NegotiatedRate> negotiated, Instant at, String retailOrgId) {
        AppliedRate rate = rateFor(kind, orgId, category, planCode, negotiated, at, retailOrgId);
        boolean retail = rate.source() == RateSource.RETAIL;
        return new EffectiveFees(id, version, effectiveFrom, currency, rate.category(), orgId, rate.bps(),
                rate.source().name(), cardPctBps, cardFixed,
                retail || kind == FeeKind.SERVICE ? Money.zero(currency) : fbaPerUnitFor(rate.planCode()),
                reverseCommissionOnRefund,
                retail, kind, rate.planCode(), rate.negotiatedRateId(), rate.negotiatedUntil());
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
                new Cancellation(now, by, reason), deliveryPricing, planBps, connectionFee, planFbaMinor);
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
