package com.afrotransact.offers.adapter.in.module;

import java.util.ArrayList;
import java.util.List;

import com.afrotransact.module.InProcessCalls;
import com.afrotransact.offers.api.PublicOffersApi;
import com.afrotransact.offers.application.BuyBoxView;
import com.afrotransact.offers.application.ListedOffer;
import com.afrotransact.offers.application.OfferListingService;
import com.afrotransact.offers.domain.Offer;
import com.afrotransact.offers.domain.StockStatus;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link PublicOffersApi}: the anonymous storefront read behind
 * {@code GET /v1/public/products/{productAtin}/buy-boxes}, flattened to one row per listed offer, winners first. Currency defaults to USD
 * as over HTTP. No scope over HTTP, none here; a caller principal is still required so that no code path reaches it unnamed.
 */
@Component
class PublicOffersApiAdapter implements PublicOffersApi {

    private final OfferListingService listing;

    PublicOffersApiAdapter(OfferListingService listing) {
        this.listing = listing;
    }

    @Override
    public List<OfferRow> offersForProduct(String productAtin, String currency) {
        InProcessCalls.require();
        List<OfferRow> out = new ArrayList<>();
        for (BuyBoxView box : listing.productBuyBoxes(productAtin, currency == null || currency.isBlank() ? "USD" : currency)) {
            if (box.winner() != null) {
                out.add(row(box.winner()));
            }
            box.otherOffers().forEach(o -> out.add(row(o)));
        }
        return out;
    }

    private static OfferRow row(ListedOffer listed) {
        Offer o = listed.view().offer();
        String seller = listed.view().sellerName() == null ? o.sellerOrgId() : listed.view().sellerName();
        return new OfferRow(o.id().toString(), seller, o.sellerKind().name(), o.fulfilment().name(), o.priceMinor(),
                o.currency(), listed.view().stockStatus() != StockStatus.OUT_OF_STOCK, listed.view().deliveryDays(),
                o.condition().name());
    }
}
