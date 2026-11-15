package com.afrotransact.orders.adapter.in.module;

import com.afrotransact.orders.api.OrderLookupApi;
import com.afrotransact.orders.application.OrderLookup;
import com.afrotransact.orders.application.OrderLookupService;
import com.afrotransact.orders.application.PurchaseCheck;
import com.afrotransact.orders.application.PurchaseCheckService;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link OrderLookupApi}: the use cases behind {@code GET /v1/lookup/orders/{id}} and
 * {@code GET /v1/lookup/customers/{customerId}/purchases/{atin}}, entered under the calling module's principal. The services
 * require {@code orders:lookup} / {@code orders:purchase-check} (or staff) exactly as for a token.
 */
@Component
class OrderLookupApiAdapter implements OrderLookupApi {

    private final OrderLookupService lookups;
    private final PurchaseCheckService purchases;
    private final ModuleEntry entry;

    OrderLookupApiAdapter(OrderLookupService lookups, PurchaseCheckService purchases, ActorResolver actors) {
        this.lookups = lookups;
        this.purchases = purchases;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public OrderView lookup(String idOrNumber) {
        return entry.run(() -> view(lookups.lookup(idOrNumber)), OrdersFailures::map);
    }

    @Override
    public PurchaseProof purchase(String customerId, String productAtin) {
        return entry.run(() -> {
            PurchaseCheck c = purchases.check(customerId, productAtin);
            return new PurchaseProof(c.delivered(), c.orderId(), c.orderNumber(), c.shipmentId(), c.deliveredAt());
        }, OrdersFailures::map);
    }

    private static OrderView view(OrderLookup o) {
        var to = o.shipTo();
        return new OrderView(o.orderId(), o.orderNumber(), o.customerId(), o.status(), o.currency(), o.paymentId(),
                o.placedAt(), o.itemsMinor(), o.shippingMinor(), o.taxMinor(), o.discountMinor(), o.totalMinor(),
                to == null ? null : new ShipTo(to.fullName(), to.phone(), to.line1(), to.line2(), to.city(), to.state(),
                        to.postalCode(), to.country()),
                o.shipments().stream().map(OrderLookupApiAdapter::shipment).toList());
    }

    private static Shipment shipment(OrderLookup.Shipment s) {
        return new Shipment(s.shipmentId(), s.seq(), s.sellerOrgId(), s.sellerName(), s.fulfilmentType(),
                s.deliveryMethod(), s.nodeId(), s.status(), s.perishable(), s.shippingMinor(), s.totalMinor(),
                s.carrier(), s.trackingNumber(), s.promisedBy(), s.shipBy(), s.deliveredAt(), s.captured(),
                s.returnWindowEndsAt(), s.lines().stream().map(l -> new Line(l.lineId(), l.lineNo(), l.variantAtin(),
                l.productAtin(), l.title(), l.productType(), l.category(), l.perishable(), l.quantity(),
                l.unitPriceMinor(), l.discountMinor(), l.taxMinor(), l.shippingMinor(), l.shippingTaxMinor(),
                l.taxJurisdiction(), l.localTaxJurisdiction(), l.taxOutcome(), l.internalTaxCode(), l.providerTaxCode(),
                l.taxRuleVersion(), l.taxProvider())).toList());
    }
}
