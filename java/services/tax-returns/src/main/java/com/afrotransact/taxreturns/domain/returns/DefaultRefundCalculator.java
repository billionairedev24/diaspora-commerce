package com.afrotransact.taxreturns.domain.returns;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Refund math. All proportions use {@link BigDecimal} and round <b>half up to the minor unit per line</b>.
 *
 * <ul>
 *   <li><b>Items</b>: {@code itemsCharged × returnedQty / qty}; returning the last remaining units refunds exactly
 *       what is left, so several partial returns never drift from the charged amount.</li>
 *   <li><b>Tax</b>: proportional to the units in the same way (the full tax of the returned units is refunded even
 *       when a restocking fee is kept: the fee is treated as a separate, non-taxable charge).</li>
 *   <li><b>Shipping</b>: only when the seller is at fault, per shipment: the shipment's shipping × (value of the
 *       seller-fault units / items value of the shipment); everything left when the return empties the shipment. It
 *       is allocated to the lines that carry shipping (orders charges it on the shipment's first line).</li>
 *   <li><b>Restocking fee</b>: no-fault lines only, {@code items × bps / 10 000}, never more than the items.</li>
 * </ul>
 */
public final class DefaultRefundCalculator implements RefundCalculator {

    private static final long BPS = 10_000L;

    @Override
    public RefundBreakdown forReturn(List<RefundableLine> charged, List<RefundSelection> selections,
                                     int restockingFeeBps) {
        if (restockingFeeBps < 0 || restockingFeeBps > BPS) {
            throw new IllegalArgumentException("restockingFeeBps must be between 0 and 10000");
        }
        Map<String, RefundableLine> byId = new LinkedHashMap<>();
        charged.forEach(l -> byId.put(l.lineId(), l));
        Map<String, long[]> amounts = new HashMap<>();
        Map<String, Integer> quantities = new HashMap<>();
        Map<String, Long> faultItemsByShipment = new LinkedHashMap<>();
        Map<String, Integer> selectedUnitsByShipment = new HashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (RefundSelection s : selections) {
            RefundableLine l = byId.get(s.lineId());
            if (l == null) {
                throw new ReturnRuleViolation("returns.unknown-line", "Line " + s.lineId() + " was not charged");
            }
            if (!seen.add(s.lineId())) {
                throw new ReturnRuleViolation("returns.duplicate-line", "Line " + s.lineId() + " is listed twice");
            }
            if (s.quantity() < 1 || s.quantity() > l.remainingQuantity()) {
                throw new ReturnRuleViolation("returns.refund-exceeds-remaining", "Line " + s.lineId()
                        + ": only " + l.remainingQuantity() + " unit(s) can still be refunded");
            }
            boolean all = s.quantity() == l.remainingQuantity();
            long gross = all ? l.remainingItemsMinor()
                    : Math.min(prorate(l.itemsMinor(), s.quantity(), l.quantity()), l.remainingItemsMinor());
            long tax = all ? l.remainingTaxMinor()
                    : Math.min(prorate(l.taxMinor(), s.quantity(), l.quantity()), l.remainingTaxMinor());
            long fee = s.sellerFault() ? 0L : Math.min(gross, prorate(gross, restockingFeeBps, BPS));
            amounts.put(l.lineId(), new long[] {gross - fee, 0L, tax, fee});
            quantities.put(l.lineId(), s.quantity());
            selectedUnitsByShipment.merge(l.shipmentId(), s.quantity(), Integer::sum);
            if (s.sellerFault()) {
                faultItemsByShipment.merge(l.shipmentId(), gross, Long::sum);
            }
        }
        for (Map.Entry<String, Long> e : faultItemsByShipment.entrySet()) {
            List<RefundableLine> shipment = charged.stream().filter(l -> e.getKey().equals(l.shipmentId())).toList();
            long remainingShipping = shipment.stream().mapToLong(RefundableLine::remainingShippingMinor).sum();
            if (remainingShipping == 0L) {
                continue;
            }
            int remainingUnits = shipment.stream().mapToInt(RefundableLine::remainingQuantity).sum();
            long share;
            if (selectedUnitsByShipment.getOrDefault(e.getKey(), 0) == remainingUnits) {
                share = remainingShipping;
            } else {
                long shipping = shipment.stream().mapToLong(RefundableLine::shippingMinor).sum();
                long items = shipment.stream().mapToLong(RefundableLine::itemsMinor).sum();
                share = Math.min(prorate(shipping, e.getValue(), items), remainingShipping);
            }
            for (RefundableLine l : shipment) {
                if (share == 0L) {
                    break;
                }
                long part = Math.min(share, l.remainingShippingMinor());
                if (part > 0L) {
                    amounts.computeIfAbsent(l.lineId(), k -> new long[4])[1] += part;
                    share -= part;
                }
            }
        }
        List<RefundLineAmount> out = new ArrayList<>();
        for (RefundableLine l : charged) {
            long[] a = amounts.get(l.lineId());
            if (a != null) {
                out.add(new RefundLineAmount(l.lineId(), quantities.getOrDefault(l.lineId(), 0), a[0], a[1], a[2],
                        a[3]));
            }
        }
        return new RefundBreakdown(out);
    }

    @Override
    public RefundLineAmount adjustment(RefundableLine line, long itemsMinor, long shippingMinor) {
        if (itemsMinor < 0 || shippingMinor < 0 || itemsMinor + shippingMinor == 0) {
            throw new ReturnRuleViolation("returns.invalid-amount", "Refund a positive amount");
        }
        if (itemsMinor > line.remainingItemsMinor()) {
            throw new ReturnRuleViolation("returns.refund-exceeds-remaining", "Line " + line.lineId()
                    + ": at most " + line.remainingItemsMinor() + " of the items amount can still be refunded");
        }
        if (shippingMinor > line.remainingShippingMinor()) {
            throw new ReturnRuleViolation("returns.refund-exceeds-remaining", "Line " + line.lineId()
                    + ": at most " + line.remainingShippingMinor() + " of the shipping can still be refunded");
        }
        long tax = itemsMinor == line.remainingItemsMinor() ? line.remainingTaxMinor()
                : Math.min(prorate(line.taxMinor(), itemsMinor, line.itemsMinor()), line.remainingTaxMinor());
        return new RefundLineAmount(line.lineId(), 0, itemsMinor, shippingMinor, tax, 0L);
    }

    /** {@code amount × numerator / denominator}, rounded half up to a whole minor unit (0 when the denominator is 0). */
    public static long prorate(long amount, long numerator, long denominator) {
        if (denominator == 0L || amount == 0L || numerator == 0L) {
            return 0L;
        }
        return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(numerator))
                .divide(BigDecimal.valueOf(denominator), 0, RoundingMode.HALF_UP).longValueExact();
    }
}
