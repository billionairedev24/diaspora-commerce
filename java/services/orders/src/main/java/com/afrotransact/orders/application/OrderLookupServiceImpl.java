package com.afrotransact.orders.application;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrderLine;
import com.afrotransact.orders.domain.ReturnWindowPolicy;
import com.afrotransact.orders.domain.ShipByPolicy;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.port.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrderLookupServiceImpl implements OrderLookupService {

    private final OrderRepository orders;
    private final OrdersAccess access;
    private final ShipByPolicy shipBy;
    private final ReturnWindowPolicy returnWindows;
    private final OrdersSettings settings;

    OrderLookupServiceImpl(OrderRepository orders, OrdersAccess access, ShipByPolicy shipBy,
                           ReturnWindowPolicy returnWindows, OrdersSettings settings) {
        this.orders = orders;
        this.access = access;
        this.shipBy = shipBy;
        this.returnWindows = returnWindows;
        this.settings = settings;
    }

    @Override
    @Transactional(readOnly = true)
    public OrderLookup lookup(String idOrNumber) {
        access.lookupCaller();
        Order o = find(idOrNumber);
        LocalDate placedOn = o.placedAt() == null ? null
                : LocalDate.ofInstant(o.placedAt(), ZoneId.of(settings.zone()));
        List<OrderLookup.Shipment> shipments = o.shipments().stream().map(s -> shipment(s, placedOn)).toList();
        return new OrderLookup(o.id(), o.number(), o.customerId(), o.status().name().toLowerCase(Locale.ROOT),
                o.currency(), o.paymentId(), o.placedAt(), o.totals().itemsMinor(), o.totals().shippingMinor(),
                o.totals().taxMinor(), o.totals().discountMinor(), o.totals().grandTotalMinor(), o.shipTo(),
                shipments);
    }

    private OrderLookup.Shipment shipment(Shipment s, LocalDate placedOn) {
        List<OrderLine> sorted = s.lines().stream().sorted(Comparator.comparingInt(OrderLine::lineNo)).toList();
        List<OrderLookup.Line> lines = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            OrderLine l = sorted.get(i);
            lines.add(new OrderLookup.Line(l.id(), l.lineNo(), l.variantAtin(), l.productAtin(), l.title(),
                    l.productType(), l.category(), l.perishable(), l.quantity(), l.unitPriceMinor(),
                    l.discountMinor(), l.taxMinor(), i == 0 ? s.shippingMinor() : 0, l.shippingTaxMinor(),
                    l.taxJurisdiction(), l.localTaxJurisdiction()));
        }
        return new OrderLookup.Shipment(s.id(), s.seq(), s.sellerOrgId(), s.sellerName(), s.fulfilmentType().name(),
                s.deliveryMethod().name(), s.nodeId(), s.status().wire(), s.perishable(), s.shippingMinor(),
                s.totalMinor(), s.carrier(), s.trackingNumber(), s.promisedBy(),
                shipBy.shipBy(placedOn, s.fulfilmentType(), s.deliveryMethod()), s.deliveredAt(), s.captured(),
                returnWindows.windowEndsAt(s).orElse(null), lines);
    }

    private Order find(String idOrNumber) {
        String key = idOrNumber == null ? "" : idOrNumber.trim();
        Optional<Order> byNumber = orders.findByNumber(key.toUpperCase(Locale.ROOT));
        if (byNumber.isPresent()) {
            return byNumber.get();
        }
        try {
            UUID.fromString(key);
            return orders.findById(key).orElseThrow(() -> OrdersAccess.notFound(idOrNumber));
        } catch (IllegalArgumentException notUuid) {
            throw OrdersAccess.notFound(idOrNumber);
        }
    }
}
