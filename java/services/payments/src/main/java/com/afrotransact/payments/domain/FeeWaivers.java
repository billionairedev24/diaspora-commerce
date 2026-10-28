package com.afrotransact.payments.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The one place the membership delivery benefits are resolved (ADR 0005 section 3, ADR 0006 section 4). Checkout, the
 * cart and the BFF ask payments; payments applies this rule to the fees the delivery fee rules produced:
 *
 * <ul>
 *   <li>AfroTransact delivery (store delivery, pre-order windows, delivery runs) is waived by FREE_DELIVERY,
 *       carrier shipping by FREE_SHIPPING; pickup fees and express upgrades are never waived.</li>
 *   <li>The order's goods subtotal must reach the benefit's minimum.</li>
 *   <li>A benefit waives at most its per-order cap in total across the order's shipments.</li>
 * </ul>
 */
public final class FeeWaivers {

    private FeeWaivers() {
    }

    /** What kind of delivery fee the rules produced. */
    public enum Kind {
        AFROTRANSACT_DELIVERY, CARRIER, PICKUP, EXPRESS;

        /** The benefit that waives this kind of fee, if any. */
        public Optional<BenefitCode> benefit() {
            return switch (this) {
                case AFROTRANSACT_DELIVERY -> Optional.of(BenefitCode.FREE_DELIVERY);
                case CARRIER -> Optional.of(BenefitCode.FREE_SHIPPING);
                case PICKUP, EXPRESS -> Optional.empty();
            };
        }
    }

    /** A fee on the order, as the delivery fee rules priced it. {@code key} is the caller's id (a shipment). */
    public record Fee(String key, Kind kind, long feeMinor) {
    }

    /** A fee waived because of the membership, with the benefit that did it. */
    public record Waiver(String key, BenefitCode benefit, long waivedMinor) {
    }

    /** The waivers a tier gives on an order with this goods subtotal and these fees. */
    public static List<Waiver> resolve(MembershipTier tier, long itemsMinor, List<Fee> fees) {
        List<Waiver> out = new ArrayList<>();
        long[] left = new long[BenefitCode.values().length];
        boolean[] started = new boolean[BenefitCode.values().length];
        for (Fee fee : fees) {
            Optional<BenefitCode> code = fee.kind().benefit();
            if (code.isEmpty() || fee.feeMinor() <= 0) {
                continue;
            }
            Optional<TierBenefit> benefit = tier.benefit(code.get());
            if (benefit.isEmpty() || itemsMinor < benefit.get().minOrder()) {
                continue;
            }
            int i = code.get().ordinal();
            if (!started[i]) {
                started[i] = true;
                left[i] = benefit.get().capMinor() == null ? Long.MAX_VALUE : benefit.get().capMinor();
            }
            long waived = Math.min(fee.feeMinor(), left[i]);
            if (waived > 0) {
                left[i] -= waived;
                out.add(new Waiver(fee.key(), code.get(), waived));
            }
        }
        return out;
    }

    /** The total waived. */
    public static long total(List<Waiver> waivers) {
        return waivers.stream().mapToLong(Waiver::waivedMinor).sum();
    }
}
