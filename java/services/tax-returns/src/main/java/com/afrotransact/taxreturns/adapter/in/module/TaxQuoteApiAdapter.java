package com.afrotransact.taxreturns.adapter.in.module;

import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import com.afrotransact.taxreturns.api.TaxQuoteApi;
import com.afrotransact.taxreturns.application.TaxQuoteCommand;
import com.afrotransact.taxreturns.application.TaxService;
import com.afrotransact.taxreturns.domain.tax.LineDecision;
import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.domain.tax.TaxLineResult;
import com.afrotransact.taxreturns.domain.tax.TaxQuote;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link TaxQuoteApi}: {@code POST /v1/tax/quotes} under the calling module's principal ({@code tax:quote}
 * is checked by {@link TaxService} as for a token). The request is built the way the controller builds it; the answer carries
 * the fields the controller's response carries for orders.
 */
@Component
class TaxQuoteApiAdapter implements TaxQuoteApi {

    private final TaxService tax;
    private final ModuleEntry entry;

    TaxQuoteApiAdapter(TaxService tax, ActorResolver actors) {
        this.tax = tax;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public Quote quote(QuoteRequest r) {
        return entry.run(() -> {
            TaxQuote q = tax.quote(new TaxQuoteCommand(r.currency(),
                    new TaxAddress(r.shipTo().country(), r.shipTo().state(), r.shipTo().postalCode(), r.shipTo().city(),
                            r.shipTo().line1(), null),
                    r.lines().stream().map(l -> new TaxQuoteCommand.Line(l.lineId(), l.atin(), l.sellerOrgId(), null,
                            l.productType(), l.quantity(), l.unitPriceMinor(), l.discountMinor(), l.shippingMinor(),
                            l.sellerBehaviour(), l.shipFrom() == null ? null : new TaxAddress(l.shipFrom().country(),
                            l.shipFrom().state(), l.shipFrom().postalCode(), l.shipFrom().city(), l.shipFrom().line1(),
                            null))).toList(), null, r.orderId()));
            return new Quote(q.provider(), q.lines().stream().map(l -> line(l, q.provider())).toList());
        }, TaxReturnsFailures::map);
    }

    private static LineTax line(TaxLineResult r, String provider) {
        LineDecision d = r.decision();
        String providerCode = d == null ? null : d.providerCodes().get(providerId(provider));
        return new LineTax(r.lineId(), r.taxMinor(), r.shippingTaxMinor(), r.jurisdiction(), r.localJurisdiction(),
                d == null ? null : d.status().name(), d == null || d.outcome() == null ? null : d.outcome().name(),
                d == null ? null : d.internalTaxCode(), providerCode, d == null ? null : d.ruleVersion(),
                d == null ? null : d.blockedReason(), r.addressPrecision());
    }

    private static String providerId(String quoteProvider) {
        return "STRIPE_TAX".equals(quoteProvider) ? "STRIPE" : "AVALARA".equals(quoteProvider) ? "AVALARA" : "";
    }
}
