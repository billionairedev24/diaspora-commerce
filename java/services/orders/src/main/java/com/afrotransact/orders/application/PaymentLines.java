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
 * first line, discounts netted from items. Captures and refunds use the same allocation.
 */
final class PaymentLines {

    private PaymentLines() {
    }

    static List<PaymentsClient.Line> of(Order order) {
        List<PaymentsClient.Line> out = new ArrayList<>();
        for (Shipment s : order.shipments()) {
            List<OrderLine> lines = sorted(s);
            for (int i = 0; i < lines.size(); i++) {
                OrderLine l = lines.get(i);
                out.add(new PaymentsClient.Line(l.id(), s.sellerOrgId(), l.category(),
                        s.fulfilmentType().afrotransactFulfilled() ? "FBA" : "SELF", l.quantity(),
                        l.itemsMinor() - l.discountMinor(), i == 0 ? s.shippingMinor() : 0, l.taxMinor(),
                        l.taxJurisdiction(), l.shippingTaxMinor()));
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

    static List<String> lineIds(Shipment s) {
        return sorted(s).stream().map(OrderLine::id).toList();
    }

    private static List<OrderLine> sorted(Shipment s) {
        return s.lines().stream().sorted(Comparator.comparingInt(OrderLine::lineNo)).toList();
    }
}
