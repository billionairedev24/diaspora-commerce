package com.afrotransact.orders.application;

import java.util.List;

import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrderLine;
import com.afrotransact.orders.domain.OrderStatus;
import com.afrotransact.orders.domain.OrderTotals;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.domain.ShipmentStatus;

/** Turns a saga draft into the domain order (PENDING_PAYMENT). Pure. */
final class OrderDrafts {

    private OrderDrafts() {
    }

    static Order toOrder(OrderDraft d, String paymentId) {
        List<Shipment> shipments = d.shipments().stream().map(s -> new Shipment(s.id(), d.orderId(), s.seq(),
                s.sellerOrgId(), s.sellerName(), s.fulfilmentType(), s.nodeId(), s.method(), s.serviceLevel(),
                s.pickupLocationId(), s.preferences(), s.perishable(), s.handoverRequired(), s.handoverCodeHash(),
                s.handoverCodeSealed(), ShipmentStatus.PENDING_PAYMENT, s.shippingMinor(), null, null,
                s.promisedFrom(), s.promisedBy(), null, null, null, null, d.createdAt(),
                s.lines().stream().map(OrderDrafts::line).toList(), 0, s.localDelivery(), s.preorder(),
                s.feeRule(), s.membershipWaiver())).toList();
        return new Order(d.orderId(), d.orderNumber(), d.customerId(), d.currency(), OrderStatus.PENDING_PAYMENT,
                OrderTotals.ofShipments(shipments), d.shipTo(), paymentId, d.workflowId(), null, null, d.createdAt(),
                shipments, 0, d.origin());
    }

    private static OrderLine line(CheckoutPlan.Line l) {
        return new OrderLine(l.lineId(), l.lineNo(), l.variantAtin(), l.productAtin(), l.title(), l.imageUrl(),
                l.quantity(), l.unitPriceMinor(), l.taxMinor(), l.discountMinor(), l.category(), l.perishable(),
                l.taxJurisdiction(), l.productType(), l.localTaxJurisdiction(), l.shippingTaxMinor(), l.sellerOrgId(),
                l.sellerName());
    }
}
