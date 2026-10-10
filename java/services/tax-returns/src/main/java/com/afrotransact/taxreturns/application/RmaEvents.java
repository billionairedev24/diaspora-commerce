package com.afrotransact.taxreturns.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.taxreturns.domain.returns.Refund;
import com.afrotransact.taxreturns.domain.returns.RefundLineAmount;
import com.afrotransact.taxreturns.domain.returns.Rma;
import com.afrotransact.taxreturns.domain.returns.RmaLine;
import com.afrotransact.taxreturns.port.RmaRepository;
import org.springframework.stereotype.Component;

/** Persists RMA changes with their audit trail and outbox event (call inside a transaction). */
@Component
class RmaEvents {

    private final RmaRepository rmas;
    private final DomainEventPublisher events;

    RmaEvents(RmaRepository rmas, DomainEventPublisher events) {
        this.rmas = rmas;
        this.events = events;
    }

    /** Updates the RMA, records the status change and publishes {@code type} (null: no event). */
    Rma save(Rma before, Rma after, String type, String actor, String note) {
        Rma saved = rmas.update(after);
        if (before.status() != saved.status()) {
            rmas.recordTransition(saved.id(), before.status(), saved.status(), actor, note, saved.updatedAt());
        }
        if (type != null) {
            publish(type, saved, actor);
        }
        return saved;
    }

    void publish(String type, Rma r, String actor) {
        events.publish(type, TaxReturnsEvents.AGGREGATE, r.id().toString(), payload(r, actor));
    }

    void refund(String type, Refund r, String rmaNumber, Instant at) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("refundId", r.id().toString());
        p.put("kind", r.kind().name());
        p.put("rmaId", r.rmaId() == null ? null : r.rmaId().toString());
        p.put("rmaNumber", rmaNumber);
        p.put("orderId", r.orderId().toString());
        p.put("paymentId", r.paymentId().toString());
        p.put("paymentsRefundId", r.paymentsRefundId());
        p.put("status", r.status().name());
        p.put("currency", r.currency());
        p.put("itemsMinor", r.breakdown().itemsMinor());
        p.put("shippingMinor", r.breakdown().shippingMinor());
        p.put("taxMinor", r.breakdown().taxMinor());
        p.put("restockingFeeMinor", r.breakdown().restockingFeeMinor());
        p.put("totalMinor", r.breakdown().totalMinor());
        p.put("failure", r.failure());
        p.put("lines", r.lines().stream().map(RmaEvents::line).toList());
        p.put("occurredAt", at);
        events.publish(type, TaxReturnsEvents.AGGREGATE, r.id().toString(), p);
    }

    private static Map<String, Object> line(RefundLineAmount l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lineId", l.lineId());
        m.put("quantity", l.quantity());
        m.put("itemsMinor", l.itemsMinor());
        m.put("shippingMinor", l.shippingMinor());
        m.put("taxMinor", l.taxMinor());
        m.put("restockingFeeMinor", l.restockingFeeMinor());
        return m;
    }

    static Map<String, Object> payload(Rma r, String actor) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("rmaId", r.id().toString());
        p.put("number", r.number());
        p.put("orderId", r.orderId().toString());
        p.put("orderNumber", r.orderNumber());
        p.put("shipmentId", r.shipmentId().toString());
        p.put("customerId", r.customerId());
        p.put("sellerOrgId", r.sellerOrgId());
        p.put("fulfilmentType", r.fulfilment().name());
        p.put("method", r.method().name());
        p.put("status", r.status().name());
        p.put("autoApproved", r.autoApproved());
        p.put("refundAtFirstScan", r.refundAtFirstScan());
        p.put("refundState", r.refundState().name());
        p.put("currency", r.currency());
        p.put("estimateMinor", r.estimateMinor());
        p.put("approvalDueAt", r.approvalDueAt());
        p.put("carrier", r.label() == null ? null : r.label().carrier());
        p.put("trackingNumber", r.label() == null ? null : r.label().trackingNumber());
        p.put("dropOffBy", r.dropOffBy());
        p.put("decisionNote", r.decisionNote());
        p.put("actor", actor);
        p.put("lines", lines(r.lines()));
        p.put("occurredAt", r.updatedAt());
        return p;
    }

    private static List<Map<String, Object>> lines(List<RmaLine> lines) {
        return lines.stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lineId", l.lineId());
            m.put("atin", l.atin());
            m.put("quantity", l.quantity());
            m.put("reason", l.reason().name());
            m.put("outcome", l.outcome() == null ? null : l.outcome().name());
            m.put("condition", l.condition() == null ? null : l.condition().name());
            return m;
        }).toList();
    }
}
