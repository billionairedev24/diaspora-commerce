package com.afrotransact.deliveryruns.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * The one place a delivery fee is resolved (ADR 0005 §3): the staff-configured rules of every method, plus how far a
 * window delivers. Window checkout, late adds, runs and carrier shipping all ask here, so the amount shown at checkout,
 * the amount charged and the snapshot kept on the order cannot differ.
 *
 * <ol>
 *   <li>A fee staff set on the window itself wins ({@link FeeSource#WINDOW_OVERRIDE}).</li>
 *   <li>Else the method's rule: the distance band of the window's location, the pickup fee, or the carrier rate.</li>
 *   <li>Beyond the last distance band AfroTransact does not deliver: {@link #door} is empty and the order ships by
 *       carrier instead of going on a run.</li>
 * </ol>
 *
 * @param rules     active rules (one per method and currency)
 * @param distances how far each window delivers
 */
public record DeliveryFees(List<DeliveryFeeRule> rules, WindowDistances distances) {

    /** The rules a fresh install starts with: $7.99 inside 100 miles, free pickup, carrier $5.99 (free over $35). */
    public static final DeliveryFees DEFAULT = new DeliveryFees(List.of(
            rule(FeeMethod.AFROTRANSACT_DELIVERY, "USD", List.of(
                    new FeeBand(BigDecimal.ZERO, BigDecimal.valueOf(100), 799)), 0, 0, 0, null),
            rule(FeeMethod.PICKUP, "USD", List.of(), 0, 0, 0, null),
            rule(FeeMethod.CARRIER, "USD", List.of(), 0, 0, 599, 3500L)), WindowDistances.UNKNOWN);

    public DeliveryFees {
        rules = rules == null ? List.of() : List.copyOf(rules);
        distances = distances == null ? WindowDistances.UNKNOWN : distances;
    }

    private static DeliveryFeeRule rule(FeeMethod m, String currency, List<FeeBand> bands, long fee, long handling,
                                        long fallback, Long freeAbove) {
        return new DeliveryFeeRule(new UUID(0, m.ordinal() + 1L), m, currency, bands, fee, handling, fallback,
                freeAbove, 1, Instant.EPOCH, "system", "Default");
    }

    /** The same rules, measuring distances with {@code d}. */
    public DeliveryFees measuring(WindowDistances d) {
        return new DeliveryFees(rules, d);
    }

    public Optional<DeliveryFeeRule> rule(FeeMethod method, String currency) {
        return rules.stream().filter(r -> r.method() == method && r.currency().equals(currency)).findFirst();
    }

    // ---- windows -----------------------------------------------------------------------------------------------

    /**
     * The door fee of a window: its override, else the AfroTransact delivery rule's band for the window's distance
     * (unmeasured: the farthest band, so a fee is never understated). Empty when there is no rule for the currency or
     * the window is beyond the last band (and has no override): no door delivery there.
     */
    public Optional<FeeSnapshot> door(PreorderWindow w) {
        String currency = w.details().currency();
        Optional<DeliveryFeeRule> rule = rule(FeeMethod.AFROTRANSACT_DELIVERY, currency);
        Money override = w.details().doorFeeOverride();
        OptionalDouble measured = distances.miles(w);
        BigDecimal miles = measured.isPresent() ? BigDecimal.valueOf(measured.getAsDouble()) : null;
        if (override != null) {
            return Optional.of(new FeeSnapshot(FeeMethod.AFROTRANSACT_DELIVERY, rule.map(DeliveryFeeRule::id)
                    .orElse(null), rule.map(DeliveryFeeRule::version).orElse(0L), currency, override.minor(),
                    FeeSource.WINDOW_OVERRIDE, null, miles));
        }
        return rule.flatMap(r -> bandFor(r, miles));
    }

    /** Whether some rule can price a door delivery in this currency at all (the publish check). */
    public boolean offersDoor(String currency) {
        return rule(FeeMethod.AFROTRANSACT_DELIVERY, currency).isPresent();
    }

    /** The pickup fee of a window: its override, else the pickup rule (free when staff set none). */
    public FeeSnapshot pickup(PreorderWindow w) {
        String currency = w.details().currency();
        Optional<DeliveryFeeRule> rule = rule(FeeMethod.PICKUP, currency);
        Money override = w.details().pickupFeeOverride();
        UUID id = rule.map(DeliveryFeeRule::id).orElse(null);
        long version = rule.map(DeliveryFeeRule::version).orElse(0L);
        if (override != null) {
            return new FeeSnapshot(FeeMethod.PICKUP, id, version, currency, override.minor(),
                    FeeSource.WINDOW_OVERRIDE, null, null);
        }
        return new FeeSnapshot(FeeMethod.PICKUP, id, version, currency, rule.map(DeliveryFeeRule::feeMinor).orElse(0L),
                FeeSource.RULE, null, null);
    }

    /** The pickup fee in {@code currency} with no window (free when staff set none). */
    public FeeSnapshot pickup(String currency) {
        Optional<DeliveryFeeRule> rule = rule(FeeMethod.PICKUP, currency);
        return new FeeSnapshot(FeeMethod.PICKUP, rule.map(DeliveryFeeRule::id).orElse(null),
                rule.map(DeliveryFeeRule::version).orElse(0L), currency, rule.map(DeliveryFeeRule::feeMinor).orElse(0L),
                FeeSource.RULE, null, null);
    }

    // ---- distance, carrier --------------------------------------------------------------------------------------

    /** The AfroTransact delivery fee at {@code miles} (null = unmeasured: the farthest band); empty beyond coverage. */
    public Optional<FeeSnapshot> door(String currency, BigDecimal miles) {
        return rule(FeeMethod.AFROTRANSACT_DELIVERY, currency).flatMap(r -> bandFor(r, miles));
    }

    private static Optional<FeeSnapshot> bandFor(DeliveryFeeRule r, BigDecimal miles) {
        Optional<FeeBand> band = miles == null ? Optional.of(r.bands().getLast()) : r.band(miles);
        return band.map(b -> new FeeSnapshot(r.method(), r.id(), r.version(), r.currency(), b.feeMinor(),
                FeeSource.RULE, b.label(), miles));
    }

    /**
     * Shipping by carrier beyond AfroTransact's coverage: the carrier-gateway quote plus handling (the fallback fee
     * when there is no quote); free once the goods reach the rule's threshold. Empty without a carrier rule.
     *
     * @param quoteMinor the cheapest acceptable carrier quote, or null
     * @param itemsMinor the goods subtotal
     */
    public Optional<FeeSnapshot> carrier(String currency, Long quoteMinor, long itemsMinor) {
        return rule(FeeMethod.CARRIER, currency).map(r -> {
            if (r.freeAboveMinor() != null && itemsMinor >= r.freeAboveMinor()) {
                return new FeeSnapshot(r.method(), r.id(), r.version(), currency, 0, FeeSource.RULE,
                        "free over " + new Money(r.freeAboveMinor(), currency).decimal(), null);
            }
            if (quoteMinor != null) {
                return new FeeSnapshot(r.method(), r.id(), r.version(), currency, quoteMinor + r.handlingMinor(),
                        FeeSource.CARRIER_QUOTE, null, null);
            }
            return new FeeSnapshot(r.method(), r.id(), r.version(), currency, r.fallbackMinor(),
                    FeeSource.CARRIER_FALLBACK, null, null);
        });
    }
}
