package com.afrotransact.orders.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrderLine;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.port.PaymentsClient;

/**
 * How an order maps onto payments lines: one payments line per order line, the shipment's shipping charged on its
 * first line, discounts netted from items. Captures and refunds use the same allocation. AfroTransact Delivery from a
 * seller's store (S255) is fulfilment AFROTRANSACT_DELIVERY; its snapshotted seller fee is spread over the shipment's
 * lines by items (largest remainder), so customer-fault refunds return it pro rata.
 */
final class PaymentLines {

    private PaymentLines() {
    }

    static List<PaymentsClient.Line> of(Order order) {
        List<PaymentsClient.Line> out = new ArrayList<>();
        for (Shipment s : order.shipments()) {
            List<OrderLine> lines = sorted(s);
            String fulfilment = s.fulfilmentType().afrotransactFulfilled() ? "FBA"
                    : s.deliveredFromSellerStore() ? "AFROTRANSACT_DELIVERY" : "SELF";
            long[] fees = s.deliveredFromSellerStore() ? spread(s.localDelivery().sellerFeeMinor(), lines)
                    : new long[lines.size()];
            for (int i = 0; i < lines.size(); i++) {
                OrderLine l = lines.get(i);
                out.add(new PaymentsClient.Line(l.id(), s.sellerOrgId(), l.category(), fulfilment, l.quantity(),
                        l.itemsMinor() - l.discountMinor(), i == 0 ? s.shippingMinor() : 0, l.taxMinor(),
                        l.taxJurisdiction(), l.shippingTaxMinor(), fees[i]));
            }
        }
        return out;
    }

    static List<PaymentsClient.RefundLine> refundOf(Shipment s) {
        List<OrderLine> lines = sorted(s);
        List<PaymentsClient.RefundLine> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            OrderLine l = lines.get(i);
            out.add(new PaymentsClient.RefundLine(l.id(), l.itemsMinor() - l.discountMinor(),
                    i == 0 ? s.shippingMinor() : 0, l.taxMinor()));
        }
        return out;
    }

    /** Largest-remainder split of {@code total} by each line's net items (all on the first line when items are 0). */
    static long[] spread(long total, List<OrderLine> lines) {
        long[] out = new long[lines.size()];
        long[] weights = new long[lines.size()];
        long sum = 0;
        for (int i = 0; i < lines.size(); i++) {
            weights[i] = Math.max(0, lines.get(i).itemsMinor() - lines.get(i).discountMinor());
            sum += weights[i];
        }
        if (total <= 0 || lines.isEmpty()) {
            return out;
        }
        if (sum == 0) {
            out[0] = total;
            return out;
        }
        long given = 0;
        double[] remainders = new double[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            double exact = (double) total * weights[i] / sum;
            out[i] = (long) Math.floor(exact);
            remainders[i] = exact - out[i];
            given += out[i];
        }
        for (long left = total - given; left > 0; left--) {
            int best = 0;
            for (int i = 1; i < remainders.length; i++) {
                if (remainders[i] > remainders[best]) {
                    best = i;
                }
            }
            out[best]++;
            remainders[best] = -1;
        }
        return out;
    }

    static List<String> lineIds(Shipment s) {
        return sorted(s).stream().map(OrderLine::id).toList();
    }

    private static List<OrderLine> sorted(Shipment s) {
        return s.lines().stream().sorted(Comparator.comparingInt(OrderLine::lineNo)).toList();
    }
}
