package com.afrotransact.taxreturns.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.events.InboxDeduplicator;
import com.afrotransact.taxreturns.domain.returns.DeliveryMethod;
import com.afrotransact.taxreturns.domain.returns.FulfilmentType;
import com.afrotransact.taxreturns.domain.returns.PlacedOrder;
import com.afrotransact.taxreturns.domain.returns.ReturnAddress;
import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.port.OrderLookup;
import com.afrotransact.taxreturns.port.OrderSnapshotRepository;
import com.afrotransact.taxreturns.port.ProductFacts;
import com.afrotransact.taxreturns.port.UpstreamException;
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
    private final TaxLineTagger tagger;
    private final TaxLedger ledger;
    private final InboxDeduplicator inbox;
    private final TransactionOperations tx;
    private final OrderLookup lookup;

    OrderIntakeServiceImpl(OrderSnapshotRepository snapshots, ProductFacts productFacts, TaxLedger ledger,
                           InboxDeduplicator inbox, TransactionOperations tx, OrderLookup lookup,
                           TaxLineTagger tagger) {
        this.tagger = tagger;
        this.lookup = lookup;
        this.snapshots = snapshots;
        this.productFacts = productFacts;
        this.ledger = ledger;
        this.inbox = inbox;
        this.tx = tx;
    }

    @Override
    public boolean onOrderPlaced(UUID eventId, OrderPlaced e) {
        PlacedOrder order = snapshot(e, facts(e));
        Map<String, ProductFacts.Facts> answers = answersOf(order);
        Boolean handled = tx.execute(status -> {
            if (!inbox.firstDelivery(ORDERS_CONSUMER, eventId)) {
                return false;
            }
            if (snapshots.insert(order)) {
                int lines = ledger.recordSale(order);
                tagger.tag(order, answers);
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

    @Override
    public Optional<PlacedOrder> order(UUID orderId) {
        Optional<PlacedOrder> known = snapshots.find(orderId);
        if (known.isPresent()) {
            return known;
        }
        Optional<PlacedOrder> fetched = lookup.placedOrder(orderId);
        if (fetched.isEmpty()) {
            return Optional.empty();
        }
        PlacedOrder order = fetched.get();
        tx.executeWithoutResult(status -> {
            if (snapshots.insert(order)) {
                int lines = ledger.recordSale(order);
                LOG.info("Order {} backfilled from orders-service: {} shipments, {} tax lines", order.orderNumber(),
                        order.shipments().size(), lines);
            }
        });
        return snapshots.find(orderId);
    }

    @Override
    public Optional<ReturnAddress> customerAddress(PlacedOrder order) {
        Optional<ReturnAddress> known = order.returnFrom();
        if (known.isPresent()) {
            return known;
        }
        try {
            Optional<ReturnAddress> fetched = lookup.placedOrder(order.orderId()).flatMap(PlacedOrder::returnFrom);
            fetched.ifPresent(a -> snapshots.updateAddress(order.orderId(), a));
            return fetched;
        } catch (UpstreamException ex) {
            LOG.warn("Order {}: ship-to address not available from orders ({} {})", order.orderNumber(),
                    ex.status(), ex.getMessage());
            return Optional.empty();
        }
    }

    /** The product answers (cached) that decide each line's tax code; unavailable ones classify by default. */
    private Map<String, ProductFacts.Facts> answersOf(PlacedOrder order) {
        List<String> atins = order.shipments().stream().flatMap(s -> s.lines().stream())
                .map(PlacedOrder.Line::atin).filter(a -> a != null).distinct().toList();
        try {
            return atins.isEmpty() ? Map.of() : productFacts.lookup(atins);
        } catch (RuntimeException ex) {
            LOG.warn("Product answers unavailable for order {}: {}; lines classified by default",
                    order.orderNumber(), ex.getMessage());
            return Map.of();
        }
    }

    /** Catalog facts only for lines the event did not describe (older orders-service events). */
    private Map<String, ProductFacts.Facts> facts(OrderPlaced e) {
        List<String> atins = e.shipments().stream().flatMap(s -> s.lines().stream())
                .filter(l -> l.productType() == null)
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
                String type = l.productType() != null ? l.productType() : f == null ? null : f.productType();
                boolean perishable = l.perishable() != null ? l.perishable() : f != null && f.perishable();
                lines.add(new PlacedOrder.Line(l.lineId(), l.variantAtin(), l.title(), l.quantity(),
                        l.unitPriceMinor(), l.taxMinor(), type, perishable, l.discountMinor()));
                itemsAndTax += l.unitPriceMinor() * l.quantity() - l.discountMinor() + l.taxMinor();
            }
            long shipping = s.shippingMinor() != null ? s.shippingMinor() : Math.max(0L, s.totalMinor() - itemsAndTax);
            shipments.add(new PlacedOrder.Shipment(s.shipmentId(), s.sellerOrgId(), s.sellerName(),
                    fulfilment(s.fulfilmentType()), delivery(s.deliveryMethod()), s.totalMinor(), shipping, null,
                    lines));
        }
        ReturnAddress customer = e.shipToLine1() == null ? null : new ReturnAddress(e.shipToName(), e.shipToLine1(),
                e.shipToLine2(), e.shipToCity(), e.shipToState(), e.shipToPostalCode(), e.shipToCountry(), null);
        return new PlacedOrder(e.orderId(), e.orderNumber(), e.customerId(),
                e.currency() == null ? "USD" : e.currency().toUpperCase(Locale.ROOT),
                new TaxAddress(e.shipToCountry(), e.shipToState(), e.shipToPostalCode(), e.shipToCity()),
                e.placedAt(), shipments, customer);
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
