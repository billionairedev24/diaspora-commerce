package com.afrotransact.offers.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.afrotransact.offers.application.CreateOfferCommand;
import com.afrotransact.offers.application.OfferService;
import com.afrotransact.offers.application.OfferView;
import com.afrotransact.offers.domain.Fulfilment;
import com.afrotransact.offers.domain.OfferCondition;
import com.afrotransact.offers.domain.ShipFrom;
import com.afrotransact.platform.web.error.BusinessRuleException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The plan's listing cap (seller-service entitlements, stubbed): a seller can have that many live listings. Pausing or
 * withdrawing one makes room; resuming checks the cap again. There is no limit on shops or users.
 */
class ListingLimitIT extends OffersIntegrationTest {

    private static final String ORG = "org_listingcappedseller01";

    private final OfferService offers;

    @Autowired
    ListingLimitIT(JdbcClient jdbc, OfferService offers) {
        super(jdbc);
        this.offers = offers;
    }

    @AfterEach
    void uncap() {
        StubListingEntitlements.clear(ORG);
    }

    private static CreateOfferCommand offer(String atin, String sku) {
        return new CreateOfferCommand(atin, null, null, sku, null, OfferCondition.NEW, 1299, null, "USD", 0,
                Fulfilment.SELLER, 5, 2, new ShipFrom("US", "TX", "Houston", "77002", null));
    }


    @Test
    void aSellerCannotHaveMoreLiveListingsThanThePlanAllows() {
        StubListingEntitlements.limit(ORG, "Seller Essentials", 2);
        TestActors.as(TestActors.seller(ORG));
        String a = atin();
        String b = atin();
        String c = atin();
        for (String v : new String[] {a, b, c}) {
            StubCatalog.live(atin(), v, null, ORG);
        }
        OfferView first = offers.create(offer(a, "CAP-1"));
        offers.create(offer(b, "CAP-2"));
        assertThatThrownBy(() -> offers.create(offer(c, "CAP-3"))).isInstanceOfSatisfying(BusinessRuleException.class,
                e -> {
                    assertThat(e.code()).isEqualTo("offers.listing-limit-reached");
                    assertThat(e.getMessage()).contains("Seller Essentials plan allows 2 live listings")
                            .contains("you have 2");
                });

        // A paused listing is not live: room for another, but resuming it needs room again.
        offers.pause(first.offer().id());
        OfferView third = offers.create(offer(c, "CAP-3"));
        assertThatThrownBy(() -> offers.resume(first.offer().id())).isInstanceOf(BusinessRuleException.class);
        offers.withdraw(third.offer().id());
        assertThat(offers.resume(first.offer().id()).offer().status().name()).isEqualTo("ACTIVE");

        // A bigger plan (or no cap) lifts it.
        StubListingEntitlements.limit(ORG, "Seller Growth", 1000);
        String d = atin();
        StubCatalog.live(atin(), d, null, ORG);
        assertThat(offers.create(offer(d, "CAP-4")).offer().sellerSku()).isEqualTo("CAP-4");
    }

    @Test
    void sellersWithoutACapAreNeverBlocked() {
        TestActors.as(TestActors.seller(ORG));
        for (int i = 0; i < 3; i++) {
            String v = atin();
            StubCatalog.live(atin(), v, null, ORG);
            offers.create(offer(v, "FREE-" + i));
        }
        assertThat(jdbc.sql("select count(*) from offer where seller_org = :o and status = 'ACTIVE'").param("o", ORG)
                .query(Long.class).single()).isGreaterThanOrEqualTo(3);
    }
}
