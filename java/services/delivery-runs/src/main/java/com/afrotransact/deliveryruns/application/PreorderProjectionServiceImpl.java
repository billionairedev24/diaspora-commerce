package com.afrotransact.deliveryruns.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.AttributionChannel;
import com.afrotransact.deliveryruns.domain.PreorderFulfilment;
import com.afrotransact.deliveryruns.domain.PreorderOrder;
import com.afrotransact.deliveryruns.domain.PreorderOrderStatus;
import com.afrotransact.deliveryruns.domain.PreorderPackage;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.domain.RefundStatus;
import com.afrotransact.deliveryruns.port.PreorderOrderRepository;
import com.afrotransact.deliveryruns.port.PreorderSequences;
import com.afrotransact.deliveryruns.port.PreorderWindowRepository;
import com.afrotransact.platform.events.InboxDeduplicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PreorderProjectionServiceImpl implements PreorderProjectionService {

    static final int ORDER_NUMBER_START = 1001;

    private static final Logger LOG = LoggerFactory.getLogger(PreorderProjectionServiceImpl.class);

    private final PreorderOrderRepository orders;
    private final PreorderWindowRepository windows;
    private final PreorderSequences sequences;
    private final InboxDeduplicator inbox;
    private final Clock clock;

    PreorderProjectionServiceImpl(PreorderOrderRepository orders, PreorderWindowRepository windows,
                                  PreorderSequences sequences, InboxDeduplicator inbox, Clock clock) {
        this.orders = orders;
        this.windows = windows;
        this.sequences = sequences;
        this.inbox = inbox;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void onOrderPlaced(UUID eventId, OrderPlaced e) {
        if (e.packages().isEmpty() || !inbox.firstDelivery(CONSUMER, eventId)) {
            return;
        }
        UUID windowId = e.packages().get(0).windowId();
        List<OrderPlaced.Package> mine = e.packages().stream().filter(p -> windowId.equals(p.windowId())).toList();
        if (mine.size() < e.packages().size()) {
            LOG.warn("Order {} has pre-order packages for several windows; projecting window {} only", e.orderId(),
                    windowId);
        }
        Optional<PreorderWindow> window = windows.findById(windowId);
        if (window.isEmpty()) {
            LOG.warn("Order {} names unknown pre-order window {}; skipped", e.orderId(), windowId);
            return;
        }
        PreorderWindow w = window.get();
        Instant now = Instant.now(clock);
        List<PreorderPackage> packages = mine.stream()
                .map(p -> new PreorderPackage(p.shipmentId(), p.sellerOrgId(), p.totalMinor(), p.shippingMinor(),
                        p.lines(), false)).toList();
        PreorderFulfilment fulfilment = mine.stream().anyMatch(p -> p.fulfilment() == PreorderFulfilment.DOOR)
                ? PreorderFulfilment.DOOR : PreorderFulfilment.PICKUP;
        AttributionChannel channel = AttributionChannel.fromSource(e.src());
        String tag = AttributionChannel.trackingTag(e.src() == null || e.src().isBlank() ? channel.source() : e.src(),
                e.creative(), e.windowCode() == null || e.windowCode().isBlank() ? w.code() : e.windowCode());
        Optional<PreorderOrder> existing = orders.findById(e.orderId());
        String number = existing.map(PreorderOrder::number).orElse(null);
        PreorderOrder placed = new PreorderOrder(e.orderId(), number == null ? "pending" : number, w.id(),
                e.customerId(), e.shipToName(), e.phone(), e.email(),
                new PreorderOrder.Address(e.line1(), e.city(), e.region(), e.zip()), e.deliveryNote(), e.placedAt(),
                channel, tag, packages, fulfilment, e.currency(), PreorderOrderStatus.PAID, null, now, now, 0);
        if (existing.isPresent()) {
            PreorderOrder prev = existing.get();
            if (prev.status() == PreorderOrderStatus.LATE_ADD) {
                orders.update(prev.completeWith(placed, now));
            }
            return;
        }
        String assigned = w.code() + "-" + sequences.next("order:" + w.id(), ORDER_NUMBER_START);
        orders.insert(new PreorderOrder(e.orderId(), assigned, w.id(), e.customerId(), e.shipToName(), e.phone(),
                e.email(), placed.address(), e.deliveryNote(), e.placedAt(), channel, tag, packages, fulfilment,
                e.currency(), PreorderOrderStatus.PAID, null, now, now, 0));
    }

    @Override
    @Transactional
    public void onShipmentCancelled(UUID eventId, String orderId, String shipmentId, Instant at) {
        if (!inbox.firstDelivery(CONSUMER, eventId)) {
            return;
        }
        Optional<PreorderOrder> o = orderId != null ? orders.findById(orderId) : Optional.empty();
        if (o.isEmpty() && shipmentId != null) {
            o = orders.findByShipment(shipmentId);
        }
        Instant now = Instant.now(clock);
        o.filter(x -> x.status() != PreorderOrderStatus.CANCELLED)
                .ifPresent(x -> {
                    PreorderOrder cancelled = orders.update(x.cancelShipment(shipmentId, now));
                    if (cancelled.status() == PreorderOrderStatus.CANCELLED) {
                        // The cancel follow-up in orders-service refunds it; refund_settled / refund_failed follow.
                        orders.recordRefund(cancelled.orderId(), RefundStatus.PENDING, null, null, now);
                    }
                });
    }

    @Override
    @Transactional
    public void onRefund(UUID eventId, String orderId, RefundStatus status, Long amountMinor, String error,
                         Instant at) {
        if (!inbox.firstDelivery(CONSUMER, eventId)) {
            return;
        }
        if (!orders.recordRefund(orderId, status, amountMinor, error, at == null ? Instant.now(clock) : at)) {
            LOG.debug("Refund {} for order {} not recorded (not a projected pre-order, or already settled)", status,
                    orderId);
        }
    }

    @Override
    @Transactional
    public void onOrderCancelled(UUID eventId, String orderId, Instant at) {
        if (!inbox.firstDelivery(CONSUMER, eventId)) {
            return;
        }
        orders.findById(orderId).filter(x -> x.status() != PreorderOrderStatus.CANCELLED)
                .ifPresent(x -> orders.update(x.cancel(Instant.now(clock))));
    }
}
