package com.afrotransact.offers.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.afrotransact.offers.application.CreateOfferCommand;
import com.afrotransact.offers.application.ListedOffer;
import com.afrotransact.offers.application.OfferBadge;
import com.afrotransact.offers.application.OfferListingService;
import com.afrotransact.offers.application.OfferService;
import com.afrotransact.offers.application.OfferView;
import com.afrotransact.offers.application.UpdateOfferCommand;
import com.afrotransact.offers.domain.Fulfilment;
import com.afrotransact.offers.domain.OfferCondition;
import com.afrotransact.offers.domain.OfferRuleViolation;
import com.afrotransact.offers.domain.ShipFrom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * S255: an offer can be AfroTransact Delivery from the seller's store, referencing one of the seller's ship-from
 * locations with AfroTransact pickup enabled (seller-service, stubbed). Existing SELLER / AFROTRANSACT offers are
 * unchanged.
 */
class AfrotransactDeliveryOfferIT extends OffersIntegrationTest {

    private final OfferService offers;
    private final OfferListingService listing;

    @Autowired
    AfrotransactDeliveryOfferIT(JdbcClient jdbc, OfferService offers, OfferListingService listing) {
        super(jdbc);
        this.offers = offers;
        this.listing = listing;
    }

    private static CreateOfferCommand delivery(String atin, String sku, String locationId) {
        return new CreateOfferCommand(atin, null, null, sku, null, OfferCondition.NEW, 1299, null, "USD", 0,
                Fulfilment.AFROTRANSACT_DELIVERY, 8, 0, locationId == null ? null
                : new ShipFrom(null, null, null, null, locationId));
    }

    @Test
    void deliveryOffersNeedOneOfTheSellersPickupLocations() {
        String product = atin();
        String variant = atin();
        StubCatalog.live(product, variant, null, LAGOS);
        String store = UUID.randomUUID().toString();
        String closed = UUID.randomUUID().toString();
        String someoneElses = UUID.randomUUID().toString();
        StubPickupLocations.put(store, LAGOS, true, "Houston");
        StubPickupLocations.put(closed, LAGOS, false, "Katy");
        StubPickupLocations.put(someoneElses, MAMATEE, true, "Houston");

        TestActors.as(TestActors.seller(LAGOS));
        assertThatThrownBy(() -> offers.create(delivery(variant, "DEL-0", null)))
                .isInstanceOfSatisfying(OfferRuleViolation.class,
                        e -> assertThat(e.fieldErrors()).containsKey("shipFrom.locationId"));
        assertThatThrownBy(() -> offers.create(delivery(variant, "DEL-1", closed)))
                .isInstanceOfSatisfying(OfferRuleViolation.class, e -> assertThat(e.fieldErrors()
                        .get("shipFrom.locationId")).contains("Enable AfroTransact pickup"));
        assertThatThrownBy(() -> offers.create(delivery(variant, "DEL-2", someoneElses)))
                .isInstanceOf(OfferRuleViolation.class);
        assertThatThrownBy(() -> offers.create(delivery(variant, "DEL-3", UUID.randomUUID().toString())))
                .isInstanceOf(OfferRuleViolation.class);

        OfferView created = offers.create(delivery(variant, "DEL-4", store));
        assertThat(created.offer().fulfilment()).isEqualTo(Fulfilment.AFROTRANSACT_DELIVERY);
        assertThat(created.offer().shipFrom().locationId()).isEqualTo(store);
        assertThat(created.offer().shipFrom().city()).as("copied from the location").isEqualTo("Houston");
        assertThat(created.availableQty()).as("seller-declared stock").isEqualTo(8);
        assertThat(created.deliveryDays()).as("handling 0 + 1 day local delivery").isEqualTo(1);

        ListedOffer listed = listing.offers(variant, "USD").winner();
        assertThat(listed.badges()).contains(OfferBadge.AFROTRANSACT_DELIVERY)
                .doesNotContain(OfferBadge.FULFILLED_BY_AFROTRANSACT, OfferBadge.SHIPS_FROM_SELLER);
        assertThat(offers.setSellerStock("DEL-4", 3, "STOCK_TAKE", null).quantity()).isEqualTo(3);

        // Switching back to SELLER keeps working, and switching to a disabled location is refused.
        assertThatThrownBy(() -> offers.update(created.offer().id(), new UpdateOfferCommand(null, null, null, null,
                null, null, null, new ShipFrom(null, null, null, null, closed), null)))
                .isInstanceOf(OfferRuleViolation.class);
        OfferView seller = offers.update(created.offer().id(), new UpdateOfferCommand(null, null, null, null,
                Fulfilment.SELLER, null, null, new ShipFrom("Houston", "TX", "US", "77002"), null));
        assertThat(seller.offer().fulfilment()).isEqualTo(Fulfilment.SELLER);
        assertThat(seller.offer().shipFrom().locationId()).isNull();
        TestActors.reset();
    }
}
