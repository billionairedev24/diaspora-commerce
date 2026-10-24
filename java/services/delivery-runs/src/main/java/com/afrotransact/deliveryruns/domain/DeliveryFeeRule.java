package com.afrotransact.deliveryruns.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The delivery fee staff configure for one method in one currency (ADR 0005 §3). Immutable; every change is a new
 * {@code version} with the reason it was made.
 * <ul>
 *   <li>{@link FeeMethod#AFROTRANSACT_DELIVERY}: {@code bands} (contiguous from 0 miles; the last band's end is how
 *       far AfroTransact delivers: beyond it the order ships by carrier).</li>
 *   <li>{@link FeeMethod#CARRIER}: the carrier-gateway quote plus {@code handlingMinor}; {@code fallbackMinor} when
 *       there is no quote; free above {@code freeAboveMinor} of goods when set.</li>
 *   <li>{@link FeeMethod#PICKUP}: {@code feeMinor} (often 0).</li>
 * </ul>
 * Fields a method does not use are zero / empty / null.
 *
 * @param bands          distance bands, sorted, contiguous from 0
 * @param feeMinor       pickup fee
 * @param handlingMinor  added to the carrier quote
 * @param fallbackMinor  carrier fee when no quote is available
 * @param freeAboveMinor goods subtotal from which carrier shipping is free (null = never)
 * @param reason         why this version was made (staff)
 */
public record DeliveryFeeRule(UUID id, FeeMethod method, String currency, List<FeeBand> bands, long feeMinor,
                              long handlingMinor, long fallbackMinor, Long freeAboveMinor, long version,
                              Instant updatedAt, String updatedBy, String reason) {

    /** Fees are between 0 and 100.00 in the currency. */
    public static final long MAX_FEE_MINOR = 10_000;
    /** Most bands one rule holds. */
    public static final int MAX_BANDS = 12;

    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    public DeliveryFeeRule {
        if (id == null || method == null) {
            throw new IllegalArgumentException("id and method are required");
        }
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw new RuleViolation("delivery-runs.invalid-currency", "Fee currencies are ISO 4217 codes");
        }
        List<FeeBand> sorted = new ArrayList<>(bands == null ? List.of() : bands);
        sorted.sort(Comparator.comparing(FeeBand::fromMiles));
        bands = List.copyOf(sorted);
        for (long fee : new long[] {feeMinor, handlingMinor, fallbackMinor}) {
            if (fee < 0 || fee > MAX_FEE_MINOR) {
                throw new RuleViolation("delivery-runs.invalid-fee", "Fees are between 0 and 100.00");
            }
        }
        if (freeAboveMinor != null && freeAboveMinor < 0) {
            throw new RuleViolation("delivery-runs.invalid-fee", "The free-shipping threshold cannot be negative");
        }
        if (method == FeeMethod.AFROTRANSACT_DELIVERY) {
            checkBands(bands);
        } else if (!bands.isEmpty()) {
            throw new RuleViolation("delivery-runs.invalid-fee-band",
                    "Only AfroTransact delivery has distance bands, not " + method);
        }
    }

    private static void checkBands(List<FeeBand> bands) {
        if (bands.isEmpty() || bands.size() > MAX_BANDS) {
            throw new RuleViolation("delivery-runs.invalid-fee-band",
                    "AfroTransact delivery needs between 1 and " + MAX_BANDS + " distance bands");
        }
        BigDecimal reached = BigDecimal.ZERO;
        for (FeeBand b : bands) {
            if (b.fromMiles().compareTo(reached) != 0) {
                throw new RuleViolation("delivery-runs.invalid-fee-band", reached.signum() == 0
                        ? "The first distance band starts at 0 miles"
                        : "Distance bands must not overlap or leave a gap: the next one starts at "
                        + reached.toPlainString() + " miles");
            }
            reached = b.toMiles();
        }
    }

    /** The band covering {@code miles}, if any. */
    public Optional<FeeBand> band(BigDecimal miles) {
        return bands.stream().filter(b -> b.covers(miles)).findFirst();
    }

    /** How far AfroTransact delivers (end of the last band); 0 for the other methods. */
    public BigDecimal coverageMiles() {
        return bands.isEmpty() ? BigDecimal.ZERO : bands.getLast().toMiles();
    }

    /** Whether these numbers are the rule's own (a save that changes nothing makes no new version). */
    public boolean sameNumbers(List<FeeBand> otherBands, long fee, long handling, long fallback, Long freeAbove) {
        List<FeeBand> sorted = new ArrayList<>(otherBands);
        sorted.sort(Comparator.comparing(FeeBand::fromMiles));
        return bands.equals(sorted) && feeMinor == fee && handlingMinor == handling && fallbackMinor == fallback
                && Objects.equals(freeAboveMinor, freeAbove);
    }

    /** The next version of this rule, with staff's new numbers. */
    public DeliveryFeeRule next(List<FeeBand> newBands, long newFee, long newHandling, long newFallback,
                                Long newFreeAbove, String by, String why, Instant now) {
        return new DeliveryFeeRule(id, method, currency, newBands, newFee, newHandling, newFallback, newFreeAbove,
                version + 1, now, by, why);
    }
}
