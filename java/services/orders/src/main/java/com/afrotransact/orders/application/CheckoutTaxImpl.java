package com.afrotransact.orders.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.port.TaxCalculator;
import com.afrotransact.orders.port.TaxQuoteClient;
import com.afrotransact.orders.port.UpstreamFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Checkout tax from tax-returns' {@code POST /v1/tax/quotes}: one quote line per order line with its discount and
 * the shipment's shipping on the shipment's first line (the same allocation payments uses). When tax-returns is
 * down or refuses and {@code fallbackToTable} is on, the interim per-state table taxes the items (no shipping tax)
 * and a warning is logged; with the fallback off the failure stops the checkout step (retried by the saga).
 */
@Service
class CheckoutTaxImpl implements CheckoutTax {

    private static final Logger LOG = LoggerFactory.getLogger(CheckoutTaxImpl.class);

    private final TaxQuoteClient quotes;
    private final TaxCalculator table;
    private final TaxSettings settings;

    CheckoutTaxImpl(TaxQuoteClient quotes, TaxCalculator table, TaxSettings settings) {
        this.quotes = quotes;
        this.table = table;
        this.settings = settings;
    }

    @Override
    public OrderDraft tax(OrderDraft draft) {
        if (settings.quoteFromTaxReturns()) {
            try {
                return quoted(draft);
            } catch (UpstreamFailure f) {
                if (!settings.fallbackToTable()) {
                    throw f;
                }
                LOG.warn("tax-returns quote failed for order {} ({} {}); falling back to the per-state tax table",
                        draft.orderNumber(), f.status(), f.code());
            }
        }
        return tabled(draft);
    }

    private OrderDraft quoted(OrderDraft draft) {
        ShipTo to = draft.shipTo();
        List<TaxQuoteClient.Line> lines = new ArrayList<>();
        for (OrderDraft.DraftShipment s : draft.shipments()) {
            List<CheckoutPlan.Line> sorted = sorted(s);
            for (int i = 0; i < sorted.size(); i++) {
                CheckoutPlan.Line l = sorted.get(i);
                lines.add(new TaxQuoteClient.Line(l.lineId(), l.variantAtin(), s.sellerOrgId(), l.productType(),
                        l.quantity(), l.unitPriceMinor(), l.discountMinor(), i == 0 ? s.shippingMinor() : 0));
            }
        }
        TaxQuoteClient.Quote quote = quotes.quote(new TaxQuoteClient.Request(draft.currency(),
                to.country() == null || to.country().isBlank() ? "US" : to.country(), to.state(), to.postalCode(),
                to.city(), lines));
        Map<String, TaxQuoteClient.LineTax> byLine = quote.lines().stream()
                .filter(t -> t.lineId() != null)
                .collect(Collectors.toMap(TaxQuoteClient.LineTax::lineId, Function.identity(), (a, b) -> a));
        if (!byLine.keySet().containsAll(lines.stream().map(TaxQuoteClient.Line::lineId).toList())) {
            throw new UpstreamFailure("tax-returns", 0, "tax-returns.incomplete-quote",
                    "The tax quote is missing some lines", true, List.of(), null);
        }
        return withLines(draft, l -> {
            TaxQuoteClient.LineTax t = byLine.get(l.lineId());
            return l.withTax(t.taxMinor(), t.jurisdiction(), t.localJurisdiction(), t.shippingTaxMinor());
        });
    }

    private OrderDraft tabled(OrderDraft draft) {
        ShipTo to = draft.shipTo();
        TaxCalculator.Rate rate = table.rateFor(to.country(), to.state(), to.postalCode());
        return withLines(draft, l -> l.withTax(rate.taxOf(Math.max(0, l.itemsMinor() - l.discountMinor())),
                rate.jurisdiction(), null, 0));
    }

    private static OrderDraft withLines(OrderDraft draft, Function<CheckoutPlan.Line, CheckoutPlan.Line> taxed) {
        List<OrderDraft.DraftShipment> shipments = draft.shipments().stream()
                .map(s -> s.with(s.lines().stream().map(taxed).toList(), s.shippingMinor())).toList();
        return new OrderDraft(draft.orderId(), draft.orderNumber(), draft.customerId(), draft.workflowId(),
                draft.currency(), draft.shipTo(), draft.reserved(), shipments, draft.createdAt(), draft.coupon());
    }

    private static List<CheckoutPlan.Line> sorted(OrderDraft.DraftShipment s) {
        return s.lines().stream().sorted(Comparator.comparingInt(CheckoutPlan.Line::lineNo)).toList();
    }
}
