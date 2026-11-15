package com.afrotransact.orders.adapter.out.module;

import com.afrotransact.module.InProcessCalls;
import com.afrotransact.orders.port.TaxQuoteClient;
import com.afrotransact.taxreturns.api.TaxQuoteApi;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * {@link TaxQuoteClient} through tax-returns' published {@link TaxQuoteApi} inside this JVM (mode {@code in-process}), entered
 * as orders' caller principal (scope {@code tax:quote}). Failures are the same {@code UpstreamFailure}s as over HTTP, which the
 * checkout tax step already handles (a missing scope is 403, an unavailable provider 503).
 */
@Component
@ConditionalOnExpression("'${at.orders.tax-returns.mode:${at.orders.mode:http}}' == 'in-process'")
class InProcessTaxQuoteClient implements TaxQuoteClient {

    private static final String SERVICE = "tax-returns";

    private final ObjectProvider<TaxQuoteApi> api;
    private final ModuleUpstream upstream;

    InProcessTaxQuoteClient(ObjectProvider<TaxQuoteApi> api, ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(TaxQuoteApi.class, true, false).length > 0, "orders",
                "tax-returns", "at.orders.tax-returns.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public Quote quote(Request r) {
        TaxQuoteApi.QuoteRequest request = new TaxQuoteApi.QuoteRequest(r.currency(),
                new TaxQuoteApi.Place(r.country(), r.state(), r.postalCode(), r.city(),
                        r.line1() == null || r.line1().isBlank() ? null : r.line1()),
                r.lines().stream().map(l -> new TaxQuoteApi.Line(l.lineId(), l.atin(), l.sellerOrgId(), l.productType(),
                        l.quantity(), l.unitPriceMinor(), l.discountMinor(), l.shippingMinor(), l.sellerBehaviour(),
                        l.shipFrom() == null ? null : new TaxQuoteApi.ShipFrom(l.shipFrom().line1(), l.shipFrom().city(),
                                l.shipFrom().state(), l.shipFrom().postalCode(), l.shipFrom().country(),
                                l.shipFrom().source()))).toList(), r.orderId());
        TaxQuoteApi.Quote q = upstream.call(SERVICE, () -> api.getObject().quote(request));
        return new Quote(q.provider(), q.lines().stream().map(l -> new LineTax(l.lineId(), l.taxMinor(),
                l.shippingTaxMinor(), l.jurisdiction(), l.localJurisdiction(), l.decisionStatus(), l.outcome(),
                l.internalTaxCode(), l.providerTaxCode(), l.ruleVersion(), l.blockedReason(), l.addressPrecision()))
                .toList());
    }
}
