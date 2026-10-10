package com.afrotransact.offers.adapter.in.web;

import java.util.List;

import com.afrotransact.offers.application.BuyBoxView;
import com.afrotransact.offers.application.ListedOffer;
import com.afrotransact.offers.application.OfferBadge;
import com.afrotransact.offers.domain.BuyBoxReason;
import com.afrotransact.offers.domain.Fulfilment;
import com.afrotransact.offers.domain.Ineligibility;
import com.afrotransact.offers.domain.Offer;
import com.afrotransact.offers.domain.OfferCondition;
import com.afrotransact.offers.domain.SellerKind;
import com.afrotransact.offers.domain.StockStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/** Storefront shapes, aligned with the gateway BFF's Offer / ProductDetail schemas. */
final class PublicDtos {

    private PublicDtos() {
    }

    @Schema(name = "OfferSeller", description = "Maps to BFF Seller (city = ship-from city or 'AfroTransact')")
    record SellerDto(String id, String name, SellerKind kind, String city) {
    }

    @Schema(name = "PublicOffer", description = "Maps 1:1 to the BFF Offer (delivery comes from promise-service)")
    record PublicOfferResponse(String offerId, String atin, String productAtin, SellerDto seller,
                               OfferCondition condition, long priceMinor, Long listPriceMinor, long shippingMinor,
                               @Schema(description = "price + shipping") long landedPriceMinor, String currency,
                               @Schema(description = "AFROTRANSACT = Fulfilled by AfroTransact; SELLER = Ships from "
                                       + "seller") Fulfilment fulfilment,
                               int availableQty, StockStatus stockStatus, int handlingDays,
                               @Schema(description = "Handling + transit estimate used by the buy box")
                               int deliveryDays, String shipsFrom, String soldBy,
                               @Schema(description = "1 = buy box winner; 0 = listed, not eligible for the buy box")
                               int buyBoxRank, double score, List<OfferBadge> badges) {

        static PublicOfferResponse of(ListedOffer l) {
            Offer o = l.view().offer();
            String shipsFrom = OfferDtos.shipsFromLabel(o);
            String name = l.view().sellerName() == null ? o.sellerOrgId() : l.view().sellerName();
            String city = o.fulfilment() == Fulfilment.AFROTRANSACT || o.shipFrom() == null ? shipsFrom
                    : o.shipFrom().city();
            return new PublicOfferResponse(o.id().toString(), o.atin(), o.productAtin(),
                    new SellerDto(o.sellerOrgId(), name, o.sellerKind(), city), o.condition(), o.priceMinor(),
                    o.listPriceMinor(), o.shippingMinor(), l.landedPriceMinor(), o.currency(), o.fulfilment(),
                    l.view().availableQty(), l.view().stockStatus(), o.handlingDays(), l.view().deliveryDays(),
                    shipsFrom, name, l.buyBoxRank(), l.score(), l.badges());
        }
    }

    @Schema(name = "BuyBox", description = "Buy box with reasons plus every other listed offer, sorted by landed "
            + "price. buyBox is null when no offer is eligible (\"Currently unavailable\").")
    record BuyBoxResponse(String atin, String currency, PublicOfferResponse buyBox, List<BuyBoxReason> reasons,
                          List<PublicOfferResponse> otherOffers, int offerCount, Long minLandedPriceMinor,
                          Long minPriceMinor) {

        static BuyBoxResponse of(BuyBoxView v) {
            return new BuyBoxResponse(v.atin(), v.currency(),
                    v.winner() == null ? null : PublicOfferResponse.of(v.winner()), v.reasons(),
                    v.otherOffers().stream().map(PublicOfferResponse::of).toList(), v.offerCount(),
                    v.minLandedPriceMinor(), v.minPriceMinor());
        }
    }

    @Schema(name = "BuyBoxList")
    record BuyBoxListResponse(List<BuyBoxResponse> items) {
    }

    @Schema(name = "PublicOfferDetail", description = "One offer for add-to-cart (works for non-buy-box offers)")
    record OfferDetailResponse(PublicOfferResponse offer,
                               @Schema(description = "False when the offer is paused, out of stock, its product is "
                                       + "not live or its seller is suppressed") boolean buyable,
                               List<Ineligibility> notEligible) {

        static OfferDetailResponse of(ListedOffer l) {
            boolean buyable = l.notEligible().stream().noneMatch(Ineligibility::unlisted);
            return new OfferDetailResponse(PublicOfferResponse.of(l), buyable, l.notEligible());
        }
    }
}
