package com.afrotransact.payments.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Splits a refund over lines in proportion to what each line still holds (captured − refunded), then within a line
 * over items, shipping and tax in proportion to their remainders. Both steps use the largest-remainder method, so
 * the shares add up to the amount exactly and no component ever exceeds what is left of it.
 */
public final class DefaultRefundAllocator implements RefundAllocator {

    @Override
    public List<Share> allocate(List<PaymentLine> lines, Money amount, RefundFault fault) {
        if (lines.isEmpty()) {
            throw new PaymentsRuleViolation("payments.invalid-refund", "A refund needs at least one line",
                    Map.of("lineIds", "Required"));
        }
        String currency = lines.getFirst().line().currency();
        long[] remaining = new long[lines.size()];
        long total = 0;
        for (int i = 0; i < lines.size(); i++) {
            PaymentLine pl = lines.get(i);
            if (!pl.isCaptured()) {
                throw new PaymentsRuleViolation("payments.line-not-captured", "Line " + pl.lineId()
                        + " has not been captured yet", Map.of("lineIds", "Line not captured"));
            }
            remaining[i] = remaining(pl, fault).total();
            total = Math.addExact(total, remaining[i]);
        }
        long wanted = amount == null ? total : amount.minor();
        if (wanted <= 0) {
            throw new PaymentsRuleViolation("payments.invalid-refund", "The refund amount must be positive",
                    Map.of("amountMinor", "Must be positive"));
        }
        if (wanted > total) {
            throw new PaymentsRuleViolation("payments.refund-exceeds-remaining", "Only " + total + " " + currency
                    + " is left to refund on these lines", Map.of("amountMinor", "At most " + total));
        }
        long[] perLine = Apportionment.largestRemainder(wanted, remaining, 0);
        List<Share> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (perLine[i] == 0) {
                continue;
            }
            Remaining r = remaining(lines.get(i), fault);
            long[] parts = Apportionment.largestRemainder(perLine[i],
                    new long[] {r.items(), r.shipping(), r.tax()}, 0);
            out.add(new Share(lines.get(i).lineId(), Money.of(parts[0], currency), Money.of(parts[1], currency),
                    Money.of(parts[2], currency)));
        }
        return List.copyOf(out);
    }

    private static Remaining remaining(PaymentLine pl, RefundFault fault) {
        OrderLine l = pl.line();
        boolean keepDeliveryCharge = fault == RefundFault.CUSTOMER
                && l.fulfilment() == Fulfilment.AFROTRANSACT_DELIVERY;
        return new Remaining(l.items().minus(pl.refundedItems()).minor(),
                keepDeliveryCharge ? 0 : l.shipping().minus(pl.refundedShipping()).minor(),
                l.tax().minus(pl.refundedTax()).minor());
    }

    private record Remaining(long items, long shipping, long tax) {

        long total() {
            return items + shipping + tax;
        }
    }
}
