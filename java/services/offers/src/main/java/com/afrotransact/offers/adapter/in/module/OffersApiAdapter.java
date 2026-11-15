package com.afrotransact.offers.adapter.in.module;

import java.util.List;
import java.util.UUID;

import com.afrotransact.offers.api.OffersApi;
import com.afrotransact.offers.application.OfferBatchService;
import com.afrotransact.offers.application.OfferService;
import com.afrotransact.offers.domain.Fulfilment;
import com.afrotransact.offers.domain.OfferCondition;
import com.afrotransact.offers.domain.OfferPackage;
import com.afrotransact.offers.domain.ShipFrom;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of the published {@link OffersApi}: the use cases behind {@code POST /v1/offers/batch},
 * {@code PUT /v1/offers/prices-and-stock} and {@code GET /v1/offers/presence}, entered under the calling module's principal.
 * It adds no rule and no permission of its own: the application services require {@code offers:write} / {@code offers:read}
 * exactly as they do for a token. It refuses to run without a caller, and when the actor in force is not that caller.
 */
@Component
class OffersApiAdapter implements OffersApi {

    private final OfferBatchService batches;
    private final OfferService offers;
    private final ModuleEntry entry;

    OffersApiAdapter(OfferBatchService batches, OfferService offers, ActorResolver actors) {
        this.batches = batches;
        this.offers = offers;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public List<RowResult> upsert(String sellerOrgId, UUID importId, String uploadedBy, List<OfferTerms> rows) {
        return entry.run(() -> batches.upsert(new OfferBatchService.UpsertBatch(sellerOrgId, importId, uploadedBy,
                rows.stream().map(OffersApiAdapter::row).toList())).stream().map(OffersApiAdapter::result).toList());
    }

    @Override
    public List<RowResult> setPricesAndStock(String sellerOrgId, UUID importId, String uploadedBy,
                                             List<PriceStock> rows) {
        return entry.run(() -> batches.setPricesAndStock(new OfferBatchService.PriceStockBatch(sellerOrgId, importId,
                uploadedBy, rows.stream().map(r -> new OfferBatchService.PriceStockRow(r.rowKey(), r.sellerSku(),
                        r.atin(), r.priceMinor(), r.listPriceMinor(), r.quantity(), r.gtin())).toList()))
                .stream().map(OffersApiAdapter::result).toList());
    }

    @Override
    public boolean hasOpenOffer(String sellerOrgId, String atin) {
        return entry.run(() -> offers.hasOpenOffer(sellerOrgId, atin));
    }

    private static OfferBatchService.UpsertRow row(OfferTerms t) {
        ShipFrom from = t.shipFrom() == null ? null : new ShipFrom(t.shipFrom().city(), t.shipFrom().region(),
                t.shipFrom().country(), t.shipFrom().postalCode());
        OfferPackage box = t.packaging() == null ? null : new OfferPackage(t.packaging().lengthIn(),
                t.packaging().widthIn(), t.packaging().heightIn(), t.packaging().weightLb());
        return new OfferBatchService.UpsertRow(t.rowKey(), t.atin(), t.sellerSku(),
                t.condition() == null ? null : OfferCondition.valueOf(t.condition()), t.priceMinor(),
                t.listPriceMinor(), t.shippingMinor(), Fulfilment.valueOf(t.fulfilment()), t.quantity(),
                t.handlingDays(), from, t.conditionNote(), box);
    }

    private static RowResult result(OfferBatchService.RowResult r) {
        return new RowResult(r.rowKey(), r.outcome().name(), r.offerId(), r.atin(),
                r.status() == null ? null : r.status().name(), r.code(), r.message());
    }
}
