package com.afrotransact.orders.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.afrotransact.orders.domain.OrdersRuleViolation;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.domain.TaxDecision;
import com.afrotransact.orders.port.TaxQuoteClient;
import com.afrotransact.orders.port.UpstreamFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Checkout tax from tax-returns' {@code POST /v1/tax/quotes}: one quote line per order line (variant ATIN and seller,
 * so tax-returns reads the stored decision) with its discount and its share of the package's delivery charge
 * ({@code at.orders.tax.shipping-allocation}, TX-05 to TX-07).
 *
 * <p>Never guesses: a line whose decision is not definite ({@code RESOLVED} with an EXEMPT or TAXABLE outcome) stops
 * the checkout with {@link CheckoutCodes#TAX_LINE_BLOCKED}; when tax-returns is down the failure is retryable. There
 * is no fallback rate. The decision each line was taxed with is kept on the line (snapshot) so refunds reverse with
 * the same code.
 */
@Service
class CheckoutTaxImpl implements CheckoutTax {

    private static final Logger LOG = LoggerFactory.getLogger(CheckoutTaxImpl.class);

    private final TaxQuoteClient quotes;
    private final TaxSettings settings;

    CheckoutTaxImpl(TaxQuoteClient quotes, TaxSettings settings) {
        this.quotes = quotes;
        this.settings = settings;
    }

    @Override
    public OrderDraft tax(OrderDraft draft) {
        ShipTo to = draft.shipTo();
        List<TaxQuoteClient.Line> lines = new ArrayList<>();
        Map<String, Long> shippingByLine = new HashMap<>();
        for (OrderDraft.DraftShipment s : draft.shipments()) {
            List<CheckoutPlan.Line> sorted = sorted(s);
            long[] weights = sorted.stream().mapToLong(l -> Math.max(0, l.itemsMinor() - l.discountMinor())).toArray();
            long[] shipping = settings.shippingAllocation().allocate(s.shippingMinor(), weights);
            for (int i = 0; i < sorted.size(); i++) {
                CheckoutPlan.Line l = sorted.get(i);
                shippingByLine.put(l.lineId(), shipping[i]);
                lines.add(new TaxQuoteClient.Line(l.lineId(), l.variantAtin(),
                        l.sellerOrgId() != null ? l.sellerOrgId() : s.sellerOrgId(), l.productType(),
                        l.quantity(), l.unitPriceMinor(), l.discountMinor(), shipping[i]));
            }
        }
        TaxQuoteClient.Quote quote = quotes.quote(new TaxQuoteClient.Request(draft.currency(),
                to.country() == null || to.country().isBlank() ? "US" : to.country(), to.state(), to.postalCode(),
                to.city(), lines));
        Map<String, TaxQuoteClient.LineTax> byLine = new LinkedHashMap<>();
        quote.lines().stream().filter(t -> t.lineId() != null).forEach(t -> byLine.putIfAbsent(t.lineId(), t));
        if (!byLine.keySet().containsAll(lines.stream().map(TaxQuoteClient.Line::lineId).toList())) {
            throw new UpstreamFailure("tax-returns", 0, "tax-returns.incomplete-quote",
                    "The tax quote is missing some lines", true, List.of(), null);
        }
        List<String> blocked = new ArrayList<>();
        for (OrderDraft.DraftShipment s : draft.shipments()) {
            for (CheckoutPlan.Line l : s.lines()) {
                TaxQuoteClient.LineTax t = byLine.get(l.lineId());
                if (!decision(t, quote.provider()).resolved()) {
                    LOG.info("checkout {} line {} has no definite tax decision (status {}, reason {})",
                            draft.orderNumber(), l.lineNo(), t.decisionStatus(), t.blockedReason());
                    blocked.add(l.title());
                }
            }
        }
        if (!blocked.isEmpty()) {
            throw new OrdersRuleViolation(CheckoutCodes.TAX_LINE_BLOCKED, blockedMessage(blocked));
        }
        return withLines(draft, l -> {
            TaxQuoteClient.LineTax t = byLine.get(l.lineId());
            return l.withTax(t.taxMinor(), t.jurisdiction(), t.localJurisdiction(), t.shippingTaxMinor(),
                    decision(t, quote.provider()), shippingByLine.get(l.lineId()));
        });
    }

    private static TaxDecision decision(TaxQuoteClient.LineTax t, String provider) {
        return new TaxDecision(t.decisionStatus(), t.outcome(), t.internalTaxCode(), t.providerTaxCode(),
                t.ruleVersion(), provider, t.addressPrecision());
    }

    /** Plain wording for the shopper: names the items, says what happens next, no tax terms and no ids. */
    static String blockedMessage(List<String> titles) {
        List<String> names = titles.stream().map(t -> t == null || t.isBlank() ? "an item" : t).distinct().toList();
        String what = names.size() == 1 ? "We can't sell " + names.getFirst() + " yet."
                : "We can't sell these items yet: " + String.join(", ", names) + ".";
        return what + " We're still checking its details. Remove " + (names.size() == 1 ? "it" : "them")
                + " from your cart to continue, or try again later.";
    }

    private static OrderDraft withLines(OrderDraft draft, Function<CheckoutPlan.Line, CheckoutPlan.Line> taxed) {
        List<OrderDraft.DraftShipment> shipments = draft.shipments().stream()
                .map(s -> s.with(s.lines().stream().map(taxed).toList(), s.shippingMinor())).toList();
        return draft.withShipments(shipments);
    }

    private static List<CheckoutPlan.Line> sorted(OrderDraft.DraftShipment s) {
        return s.lines().stream().sorted(Comparator.comparingInt(CheckoutPlan.Line::lineNo)).toList();
    }
}
