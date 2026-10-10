package com.afrotransact.payments.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One AfroTransact Delivery price: the customer's delivery charge or the seller's pickup-delivery fee.
 * <ul>
 *   <li>{@link DeliveryMode#FLAT}: {@code flatPerPackage × packages}.</li>
 *   <li>{@link DeliveryMode#DISTANCE}: per trip (one seller location → one address, however many packages): the fee
 *       of the band covering the distance when bands are set, else {@code base + perMile × miles} (half up), then
 *       raised to {@code min} and capped at {@code max} (null = no cap).</li>
 * </ul>
 * Either can be zero (a free delivery or no seller fee).
 *
 * @param perMileMinor minor units per mile (e.g. 85 = $0.85 / mi)
 */
public record DeliveryTariff(DeliveryMode mode, Money flatPerPackage, Money base, long perMileMinor,
                             List<DeliveryBand> bands, Money min, Money max) {

    public DeliveryTariff {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(flatPerPackage, "flatPerPackage");
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(min, "min");
        String currency = base.currency();
        flatPerPackage.requireSameCurrency(base);
        min.requireSameCurrency(base);
        if (max != null) {
            max.requireSameCurrency(base);
        }
        List<DeliveryBand> sorted = new ArrayList<>(bands == null ? List.of() : bands);
        sorted.sort(Comparator.comparing(DeliveryBand::fromMiles));
        for (int i = 0; i < sorted.size(); i++) {
            sorted.get(i).fee().requireSameCurrency(Money.zero(currency));
            if (i > 0 && sorted.get(i).fromMiles().compareTo(sorted.get(i - 1).toMiles()) < 0) {
                throw violation("bands", "Distance bands must not overlap");
            }
        }
        bands = List.copyOf(sorted);
        if (flatPerPackage.isNegative() || base.isNegative() || perMileMinor < 0 || min.isNegative()
                || max != null && max.isNegative()) {
            throw violation("fees", "Delivery fees cannot be negative");
        }
        if (max != null && min.greaterThan(max)) {
            throw violation("maxMinor", "The maximum must be at least the minimum");
        }
    }

    /** No charge at all (the default seller fee, or a free customer delivery). */
    public static DeliveryTariff free(String currency) {
        Money zero = Money.zero(currency);
        return new DeliveryTariff(DeliveryMode.FLAT, zero, zero, 0, List.of(), zero, null);
    }

    public static DeliveryTariff flat(Money perPackage) {
        Money zero = Money.zero(perPackage.currency());
        return new DeliveryTariff(DeliveryMode.FLAT, perPackage, zero, 0, List.of(), zero, null);
    }

    public String currency() {
        return base.currency();
    }

    /**
     * @param miles    estimated road miles of the trip (≥ 0)
     * @param packages packages handed over at the pickup (≥ 1; only FLAT counts them)
     */
    public Money price(BigDecimal miles, int packages) {
        if (miles == null || miles.signum() < 0) {
            throw violation("distanceMiles", "Distance must be ≥ 0");
        }
        if (packages < 1) {
            throw violation("packages", "At least one package");
        }
        if (mode == DeliveryMode.FLAT) {
            return flatPerPackage.times(packages);
        }
        Money fee = null;
        for (DeliveryBand b : bands) {
            if (b.covers(miles)) {
                fee = b.fee();
                break;
            }
        }
        if (fee == null) {
            long perMile = BigDecimal.valueOf(perMileMinor).multiply(miles).setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
            fee = base.plus(perMile);
        }
        fee = fee.max(min);
        return max == null ? fee : fee.min(max);
    }

    private static PaymentsRuleViolation violation(String field, String message) {
        return new PaymentsRuleViolation("payments.invalid-delivery-pricing", message, Map.of(field, message));
    }
}
