package com.afrotransact.taxreturns.adapter.out.module;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.orders.api.OrderLookupApi;
import com.afrotransact.taxreturns.domain.returns.DeliveryMethod;
import com.afrotransact.taxreturns.domain.returns.FulfilmentType;
import com.afrotransact.taxreturns.domain.returns.PlacedOrder;
import com.afrotransact.taxreturns.domain.returns.ReturnAddress;
import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.port.OrderLookup;
import com.afrotransact.taxreturns.port.UpstreamException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * {@link OrderLookup} through orders' published {@link OrderLookupApi} inside this JVM (mode {@code in-process}): the same
 * lookup as {@code GET /v1/lookup/orders/{id}}, entered as tax-returns' caller principal (scope {@code orders:lookup}). An unknown
 * order is an empty answer (404 over HTTP); other failures are the HTTP adapter's {@code UpstreamException}s. The snapshot is built
 * as the HTTP adapter builds it: cancelled shipments are left out, and an order without an id or placement time is not found.
 */
@Component
@ConditionalOnExpression("'${at.taxreturns.orders.mode:${at.taxreturns.mode:http}}' == 'in-process'")
class InProcessOrderLookup implements OrderLookup {

    private final ModuleUpstreams<OrderLookupApi> orders;

    InProcessOrderLookup(ObjectProvider<OrderLookupApi> api, ObjectProvider<CallerPrincipals> callers,
                         ListableBeanFactory beans) {
        this.orders = new ModuleUpstreams<>(OrderLookupApi.class, "orders", api, callers, beans);
    }

    @Override
    public Optional<PlacedOrder> placedOrder(UUID orderId) {
        OrderLookupApi.OrderView n;
        try {
            n = orders.call(api -> api.lookup(orderId.toString()));
        } catch (UpstreamException ex) {
            if (ex.status() == 404) {
                return Optional.empty();
            }
            throw ex;
        }
        if (n == null || n.orderId() == null || n.placedAt() == null) {
            return Optional.empty();
        }
        OrderLookupApi.ShipTo to = n.shipTo();
        List<PlacedOrder.Shipment> shipments = new ArrayList<>();
        for (OrderLookupApi.Shipment s : n.shipments()) {
            if ("cancelled".equals(s.status())) {
                continue;
            }
            List<PlacedOrder.Line> lines = new ArrayList<>();
            for (OrderLookupApi.Line l : s.lines()) {
                lines.add(new PlacedOrder.Line(l.lineId(), l.variantAtin(), l.title(), l.quantity() == 0 ? 1 : l.quantity(),
                        l.unitPriceMinor(), l.taxMinor(), l.productType(), l.perishable(), l.discountMinor()));
            }
            shipments.add(new PlacedOrder.Shipment(UUID.fromString(s.shipmentId()), s.sellerOrgId(), s.sellerName(),
                    enumOr(s.fulfilmentType(), FulfilmentType.SELLER_FULFILLED),
                    enumOr(s.deliveryMethod(), DeliveryMethod.CARRIER), s.totalMinor(), s.shippingMinor(),
                    s.deliveredAt(), lines));
        }
        String country = to == null ? null : to.country();
        ReturnAddress customer = to == null || to.line1() == null ? null : new ReturnAddress(to.fullName(), to.line1(),
                to.line2(), to.city(), to.state(), to.postalCode(), country, to.phone());
        String currency = n.currency();
        return Optional.of(new PlacedOrder(UUID.fromString(n.orderId()), n.orderNumber(), n.customerId(),
                currency == null ? "USD" : currency.toUpperCase(Locale.ROOT),
                new TaxAddress(country, to == null ? null : to.state(), to == null ? null : to.postalCode(),
                        to == null ? null : to.city()), n.placedAt(), shipments, customer));
    }

    private static <E extends Enum<E>> E enumOr(String value, E fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(fallback.getDeclaringClass(), value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
