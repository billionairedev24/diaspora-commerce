package com.afrotransact.taxreturns.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.platform.events.InboxDeduplicator;
import com.afrotransact.taxreturns.domain.returns.DeliveryMethod;
import com.afrotransact.taxreturns.domain.returns.FulfilmentType;
import com.afrotransact.taxreturns.domain.returns.PlacedOrder;
import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.port.OrderSnapshotRepository;
import com.afrotransact.taxreturns.port.ProductFacts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
class OrderIntakeServiceImpl implements OrderIntakeService {

    static final String ORDERS_CONSUMER = "taxreturns.orders";

    private static final Logger LOG = LoggerFactory.getLogger(OrderIntakeServiceImpl.class);

    private final OrderSnapshotRepository snapshots;
    private final ProductFacts productFacts;
    private final TaxLedger ledger;
    private final InboxDeduplicator inbox;
    private final TransactionOperations tx;

    OrderIntakeServiceImpl(OrderSnapshotRepository snapshots, ProductFacts productFacts, TaxLedger ledger,
                           InboxDeduplicator inbox, TransactionOperations tx) {
        this.snapshots = snapshots;
        this.productFacts = productFacts;
        this.ledger = ledger;
        this.inbox = inbox;
        this.tx = tx;
    }

    @Override
    public boolean onOrderPlaced(UUID eventId, OrderPlaced e) {
        PlacedOrder order = snapshot(e, facts(e));
        Boolean handled = tx.execute(status -> {
            if (!inbox.firstDelivery(ORDERS_CONSUMER, eventId)) {
                return false;
            }
            if (snapshots.insert(order)) {
                int lines = ledger.recordSale(order);
                LOG.info("Order {} recorded: {} shipments, {} tax lines ({})", order.orderNumber(),
                        order.shipments().size(), lines, order.shipTo().stateJurisdiction());
            }
            return true;
        });
        return Boolean.TRUE.equals(handled);
    }

    @Override
    public boolean onShipmentDelivered(UUID eventId, UUID shipmentId, Instant deliveredAt) {
        Boolean handled = tx.execute(status -> {
            if (!inbox.firstDelivery(ORDERS_CONSUMER, eventId)) {
                return false;
            }
            if (!snapshots.markDelivered(shipmentId, deliveredAt)) {
                LOG.warn("Delivered shipment {} is unknown (order not recorded); returns can't start for it",
                        shipmentId);
                return false;
            }
            return true;
        });
        return Boolean.TRUE.equals(handled);
    }

    private Map<String, ProductFacts.Facts> facts(OrderPlaced e) {
        List<String> atins = e.shipments().stream().flatMap(s -> s.lines().stream())
                .map(OrderPlaced.Line::variantAtin).filter(a -> a != null && !a.isBlank()).distinct().toList();
        if (atins.isEmpty()) {
            return Map.of();
        }
        try {
            return productFacts.lookup(atins);
        } catch (RuntimeException ex) {
            LOG.warn("Product facts unavailable for order {}: {}; lines stored without product type",
                    e.orderNumber(), ex.getMessage());
            return Map.of();
        }
    }

    static PlacedOrder snapshot(OrderPlaced e, Map<String, ProductFacts.Facts> facts) {
        List<PlacedOrder.Shipment> shipments = new ArrayList<>();
        for (OrderPlaced.Shipment s : e.shipments()) {
            List<PlacedOrder.Line> lines = new ArrayList<>();
            long itemsAndTax = 0L;
            for (OrderPlaced.Line l : s.lines()) {
                ProductFacts.Facts f = l.variantAtin() == null ? null : facts.get(l.variantAtin());
                lines.add(new PlacedOrder.Line(l.lineId(), l.variantAtin(), l.title(), l.quantity(),
                        l.unitPriceMinor(), l.taxMinor(), f == null ? null : f.productType(),
                        f != null && f.perishable()));
                itemsAndTax += l.unitPriceMinor() * l.quantity() + l.taxMinor();
            }
            shipments.add(new PlacedOrder.Shipment(s.shipmentId(), s.sellerOrgId(), s.sellerName(),
                    fulfilment(s.fulfilmentType()), delivery(s.deliveryMethod()), s.totalMinor(),
                    Math.max(0L, s.totalMinor() - itemsAndTax), null, lines));
        }
        return new PlacedOrder(e.orderId(), e.orderNumber(), e.customerId(),
                e.currency() == null ? "USD" : e.currency().toUpperCase(Locale.ROOT),
                new TaxAddress(e.shipToCountry(), e.shipToState(), e.shipToPostalCode(), e.shipToCity()),
                e.placedAt(), shipments);
    }

    private static FulfilmentType fulfilment(String value) {
        try {
            return value == null ? FulfilmentType.SELLER_FULFILLED
                    : FulfilmentType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return FulfilmentType.SELLER_FULFILLED;
        }
    }

    private static DeliveryMethod delivery(String value) {
        try {
            return value == null ? DeliveryMethod.CARRIER : DeliveryMethod.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return DeliveryMethod.CARRIER;
        }
    }
}
