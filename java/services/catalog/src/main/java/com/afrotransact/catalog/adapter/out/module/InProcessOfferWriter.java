package com.afrotransact.catalog.adapter.out.module;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.afrotransact.catalog.port.OfferWriter;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.InProcessLink;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.offers.api.OffersApi;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link OfferWriter} through offers' published API inside this JVM (mode {@code in-process}): the same batches as
 * {@code POST /v1/offers/batch} and {@code PUT /v1/offers/prices-and-stock}, entered as catalog's caller principal (scope
 * {@code offers:write}). Anything that is not a full answer (offers refusing the principal, a failure, a short result) is
 * {@link OffersUnavailable}, as over HTTP: the rows stay pending and the upload is resumed.
 */
@Component
@ConditionalOnModuleMode(caller = "catalog", callee = "offers", value = ModuleMode.IN_PROCESS)
class InProcessOfferWriter implements OfferWriter {

    private final InProcessLink<OffersApi> offers;

    InProcessOfferWriter(ObjectProvider<OffersApi> api, ObjectProvider<CallerPrincipals> callers,
                         ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(OffersApi.class, true, false).length > 0, "catalog", "offers",
                "at.catalog.offers.mode");
        this.offers = new InProcessLink<>("catalog", api::getObject, callers::getObject);
    }

    @Override
    public List<Result> upsert(String sellerOrgId, UUID importId, String uploadedBy, List<OfferTerms> rows) {
        return send(() -> offers.call(api -> api.upsert(sellerOrgId, importId, uploadedBy, rows.stream().map(t ->
                new OffersApi.OfferTerms(t.rowKey(), t.atin(), t.sellerSku(), t.condition(), t.priceMinor(),
                        t.listPriceMinor(), t.shippingMinor(), t.fulfilment(), t.quantity(), t.handlingDays(),
                        t.shipFrom() == null ? null : new OffersApi.ShipFromPlace(t.shipFrom().city(), t.shipFrom().region(),
                                t.shipFrom().country(), t.shipFrom().postalCode()), t.conditionNote(),
                        t.packaging() == null ? null : new OffersApi.PackageBox(t.packaging().lengthIn(),
                                t.packaging().widthIn(), t.packaging().heightIn(), t.packaging().weightLb())))
                .toList())), rows.size());
    }

    @Override
    public List<Result> setPricesAndStock(String sellerOrgId, UUID importId, String uploadedBy, List<PriceStock> rows) {
        return send(() -> offers.call(api -> api.setPricesAndStock(sellerOrgId, importId, uploadedBy, rows.stream()
                .map(r -> new OffersApi.PriceStock(r.rowKey(), r.sellerSku(), r.atin(), r.priceMinor(),
                        r.listPriceMinor(), r.quantity(), r.gtin())).toList())), rows.size());
    }

    private static List<Result> send(Supplier<List<OffersApi.RowResult>> call, int expected) {
        List<OffersApi.RowResult> results;
        try {
            results = call.get();
        } catch (RuntimeException e) {
            throw new OffersUnavailable("offers refused or failed the in-process batch: " + e.getMessage(), e);
        }
        if (results == null || results.size() != expected) {
            throw new OffersUnavailable("offers answered with " + (results == null ? 0 : results.size())
                    + " results for " + expected + " rows", null);
        }
        return results.stream().map(r -> new Result(r.rowKey(), r.outcome(), r.offerId(), r.atin(), r.status(),
                r.code(), r.message())).toList();
    }
}
