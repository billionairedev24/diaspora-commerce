package com.afrotransact.taxreturns.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.taxreturns.domain.returns.PlacedOrder;
import com.afrotransact.taxreturns.domain.tax.LocalTaxRate;
import com.afrotransact.taxreturns.domain.tax.TaxRateTable;
import com.afrotransact.taxreturns.domain.tax.TaxTransaction;
import com.afrotransact.taxreturns.domain.tax.TaxTransactionKind;
import com.afrotransact.taxreturns.port.OrderSnapshotRepository;
import com.afrotransact.taxreturns.port.TaxRateRepository;
import com.afrotransact.taxreturns.port.TaxTransactionRepository;
import org.springframework.stereotype.Component;

/** Writes sale and refund entries to the tax ledger (in the caller's transaction) and publishes tax events. */
@Component
class TaxLedger {

    private final TaxTransactionRepository ledger;
    private final TaxRateRepository rates;
    private final OrderSnapshotRepository orders;
    private final DomainEventPublisher events;

    TaxLedger(TaxTransactionRepository ledger, TaxRateRepository rates, OrderSnapshotRepository orders,
              DomainEventPublisher events) {
        this.ledger = ledger;
        this.rates = rates;
        this.orders = orders;
        this.events = events;
    }

    /** One SALE entry per line with what orders charged; jurisdiction from the ship-to address. */
    int recordSale(PlacedOrder order) {
        TaxRateTable table = rates.table();
        String local = table.local(order.shipTo()).map(LocalTaxRate::jurisdiction).orElse(null);
        String jurisdiction = order.shipTo().stateJurisdiction();
        int inserted = 0;
        long tax = 0L;
        List<Map<String, Object>> lines = new ArrayList<>();
        for (PlacedOrder.Shipment s : order.shipments()) {
            for (PlacedOrder.Line l : s.lines()) {
                long taxable = Math.multiplyExact(l.unitPriceMinor(), (long) l.quantity());
                TaxTransaction t = new TaxTransaction(UUID.randomUUID(), TaxTransactionKind.SALE, order.orderId(),
                        order.orderNumber(), l.lineId(), order.orderId().toString(), s.sellerOrgId(), jurisdiction,
                        local, order.shipTo().postalCode(), order.currency(), taxable, l.taxMinor(), order.placedAt());
                if (ledger.insertIfAbsent(t)) {
                    inserted++;
                    tax += l.taxMinor();
                    lines.add(line(l.lineId(), s.sellerOrgId(), taxable, l.taxMinor()));
                }
            }
        }
        if (inserted > 0) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("orderId", order.orderId().toString());
            payload.put("orderNumber", order.orderNumber());
            payload.put("jurisdiction", jurisdiction);
            payload.put("localJurisdiction", local);
            payload.put("currency", order.currency());
            payload.put("taxMinor", tax);
            payload.put("occurredAt", order.placedAt());
            payload.put("lines", lines);
            events.publish(TaxReturnsEvents.TAX_SALE_RECORDED, TaxReturnsEvents.AGGREGATE, order.orderId().toString(),
                    payload);
        }
        return inserted;
    }

    /**
     * One REFUND entry per line, keyed by the payments refund id so the orchestrator and the payments.refunded
     * consumer never book the same refund twice.
     */
    int recordRefund(UUID orderId, String sourceRef, String currency, List<ReversalLine> lines, Instant at) {
        Optional<PlacedOrder> order = orders.find(orderId);
        int inserted = 0;
        long tax = 0L;
        List<Map<String, Object>> booked = new ArrayList<>();
        for (ReversalLine r : lines) {
            if (r.taxMinor() == 0L && r.taxableMinor() == 0L) {
                continue;
            }
            Optional<TaxTransaction> sale = ledger.sale(orderId, r.lineId());
            String jurisdiction = sale.map(TaxTransaction::jurisdiction)
                    .orElseGet(() -> order.map(o -> o.shipTo().stateJurisdiction()).orElse("UNKNOWN"));
            String seller = sale.map(TaxTransaction::sellerOrgId).orElseGet(() -> sellerOf(order, r.lineId()));
            TaxTransaction t = new TaxTransaction(UUID.randomUUID(), TaxTransactionKind.REFUND, orderId,
                    sale.map(TaxTransaction::orderNumber).orElse(order.map(PlacedOrder::orderNumber).orElse(null)),
                    r.lineId(), sourceRef, seller, jurisdiction, sale.map(TaxTransaction::localJurisdiction).orElse(null),
                    sale.map(TaxTransaction::postalCode).orElse(null),
                    sale.map(TaxTransaction::currency).orElse(currency), -r.taxableMinor(), -r.taxMinor(), at);
            if (ledger.insertIfAbsent(t)) {
                inserted++;
                tax += r.taxMinor();
                booked.add(line(r.lineId(), seller, r.taxableMinor(), r.taxMinor()));
            }
        }
        if (inserted > 0) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("orderId", orderId.toString());
            payload.put("refundRef", sourceRef);
            payload.put("currency", currency);
            payload.put("taxMinor", tax);
            payload.put("occurredAt", at);
            payload.put("lines", booked);
            events.publish(TaxReturnsEvents.TAX_REFUND_REVERSED, TaxReturnsEvents.AGGREGATE, orderId.toString(),
                    payload);
        }
        return inserted;
    }

    int voidRefund(String sourceRef) {
        return ledger.voidRefund(sourceRef);
    }

    private static String sellerOf(Optional<PlacedOrder> order, String lineId) {
        return order.flatMap(o -> o.shipments().stream()
                .filter(s -> s.lines().stream().anyMatch(l -> l.lineId().equals(lineId)))
                .map(PlacedOrder.Shipment::sellerOrgId).findFirst()).orElse(null);
    }

    private static Map<String, Object> line(String lineId, String seller, long taxable, long tax) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lineId", lineId);
        m.put("sellerOrgId", seller);
        m.put("taxableMinor", taxable);
        m.put("taxMinor", tax);
        return m;
    }
}
