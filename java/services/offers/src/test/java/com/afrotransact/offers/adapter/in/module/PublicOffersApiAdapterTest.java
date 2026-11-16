package com.afrotransact.offers.adapter.in.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.afrotransact.module.InProcessCalls;
import com.afrotransact.offers.api.PublicOffersApi;
import com.afrotransact.offers.application.BuyBoxView;
import com.afrotransact.offers.application.ListedOffer;
import com.afrotransact.offers.application.OfferListingService;
import com.afrotransact.offers.application.OfferView;
import com.afrotransact.offers.domain.Fulfilment;
import com.afrotransact.offers.domain.Offer;
import com.afrotransact.offers.domain.OfferCondition;
import com.afrotransact.offers.domain.SellerKind;
import com.afrotransact.offers.domain.StockStatus;
import com.afrotransact.platform.test.InProcessKit;
import org.junit.jupiter.api.Test;

/** The public offers read for Lola: winners first then the other listed offers of every variant, public like the endpoint. */
class PublicOffersApiAdapterTest {

    private final OfferListingService listing = mock(OfferListingService.class);
    private final PublicOffersApi api = new PublicOffersApiAdapter(listing);

    private <T> T asAiGateway(Supplier<T> call) {
        return InProcessCalls.as(InProcessKit.caller("ai-gateway"), "offers", call);
    }

    private static ListedOffer listed(UUID id, String seller, StockStatus stock, long price, int days) {
        Offer offer = mock(Offer.class);
        when(offer.id()).thenReturn(id);
        when(offer.sellerOrgId()).thenReturn("org_" + seller);
        when(offer.sellerKind()).thenReturn(SellerKind.SELLER);
        when(offer.fulfilment()).thenReturn(Fulfilment.SELLER);
        when(offer.priceMinor()).thenReturn(price);
        when(offer.currency()).thenReturn("USD");
        when(offer.condition()).thenReturn(OfferCondition.NEW);
        OfferView view = mock(OfferView.class);
        when(view.offer()).thenReturn(offer);
        when(view.sellerName()).thenReturn(seller);
        when(view.stockStatus()).thenReturn(stock);
        when(view.deliveryDays()).thenReturn(days);
        ListedOffer l = mock(ListedOffer.class);
        when(l.view()).thenReturn(view);
        return l;
    }

    @Test
    void flattensTheBuyBoxesWinnerFirstAndMapsStockAndDelivery() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        ListedOffer winner = listed(a, "malo", StockStatus.LOW_STOCK, 899, 3);
        ListedOffer other = listed(b, "ada", StockStatus.OUT_OF_STOCK, 949, 5);
        when(listing.productBuyBoxes("ATPRODUCT1", "USD")).thenReturn(List.of(new BuyBoxView("ATV1", "USD", winner, List.of(),
                List.of(other), 2, 899L, 899L)));
        assertThat(asAiGateway(() -> api.offersForProduct("ATPRODUCT1", null))).containsExactly(
                new PublicOffersApi.OfferRow(a.toString(), "malo", "SELLER", "SELLER", 899, "USD", true, 3, "NEW"),
                new PublicOffersApi.OfferRow(b.toString(), "ada", "SELLER", "SELLER", 949, "USD", false, 5, "NEW"));
        verify(listing).productBuyBoxes("ATPRODUCT1", "USD");
    }

    @Test
    void aBoxWithoutAWinnerStillListsItsOtherOffers() {
        UUID b = UUID.randomUUID();
        ListedOffer other = listed(b, "ada", StockStatus.IN_STOCK, 949, 5);
        when(listing.productBuyBoxes("ATPRODUCT1", "USD")).thenReturn(List.of(new BuyBoxView("ATV1", "USD", null, List.of(),
                List.of(other), 1, null, null)));
        assertThat(asAiGateway(() -> api.offersForProduct("ATPRODUCT1", "USD"))).extracting(PublicOffersApi.OfferRow::offerId)
                .containsExactly(b.toString());
    }

    @Test
    void enteredWithoutAnInProcessCallerItFailsClosed() {
        assertThatThrownBy(() -> api.offersForProduct("ATPRODUCT1", "USD")).isInstanceOf(IllegalStateException.class);
    }
}
