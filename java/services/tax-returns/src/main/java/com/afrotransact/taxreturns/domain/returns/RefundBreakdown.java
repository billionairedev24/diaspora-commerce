package com.afrotransact.taxreturns.domain.returns;

import java.util.List;

/** Per-line refund amounts and totals. */
public record RefundBreakdown(List<RefundLineAmount> lines) {

    public RefundBreakdown {
        lines = List.copyOf(lines);
    }

    public long itemsMinor() {
        return lines.stream().mapToLong(RefundLineAmount::itemsMinor).sum();
    }

    public long shippingMinor() {
        return lines.stream().mapToLong(RefundLineAmount::shippingMinor).sum();
    }

    public long taxMinor() {
        return lines.stream().mapToLong(RefundLineAmount::taxMinor).sum();
    }

    public long restockingFeeMinor() {
        return lines.stream().mapToLong(RefundLineAmount::restockingFeeMinor).sum();
    }

    public long totalMinor() {
        return itemsMinor() + shippingMinor() + taxMinor();
    }
}
