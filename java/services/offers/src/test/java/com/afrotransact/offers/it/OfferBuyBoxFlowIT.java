package com.afrotransact.offers.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import com.afrotransact.offers.application.BuyBoxExplanation;
import com.afrotransact.offers.application.BuyBoxService;
import com.afrotransact.offers.application.BuyBoxView;
import com.afrotransact.offers.application.CreateOfferCommand;
import com.afrotransact.offers.application.ListedOffer;
import com.afrotransact.offers.application.OfferBadge;
import com.afrotransact.offers.application.OfferListingService;
import com.afrotransact.offers.application.OfferService;
import com.afrotransact.offers.application.OfferView;
import com.afrotransact.offers.application.SellerPerformanceCommand;
import com.afrotransact.offers.application.SellerStandingService;
import com.afrotransact.offers.application.StockSyncService;
import com.afrotransact.offers.application.StockUpdate;
import com.afrotransact.offers.application.UpdateOfferCommand;
import com.afrotransact.offers.domain.Fulfilment;
import com.afrotransact.offers.domain.Ineligibility;
import com.afrotransact.offers.domain.OfferCondition;
import com.afrotransact.offers.domain.OfferRuleViolation;
import com.afrotransact.offers.domain.SellerKind;
import com.afrotransact.offers.domain.ShipFrom;
import com.afrotransact.offers.domain.StockStatus;
import com.afrotransact.offers.port.BuyBoxRepository.AuditEntry;
import com.afrotransact.offers.port.StockRepository.StockLevel;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.boot.test.web.server.LocalServerPort;

/** S040 / S041 / S043 against real PostgreSQL: offers on shared ATINs, buy box recomputation, listing, audit. */
class OfferBuyBoxFlowIT extends OffersIntegrationTest {

    private static final ShipFrom HOUSTON = new ShipFrom("Houston", "TX", "US", "77002");

    private final OfferService offers;
    private final OfferListingService listing;
    private final BuyBoxService buyBox;
    private final StockSyncService stock;
    private final SellerStandingService standings;
    private final int port;

    @Autowired
    OfferBuyBoxFlowIT(JdbcClient jdbc, OfferService offers, OfferListingService listing, BuyBoxService buyBox,
                      StockSyncService stock, SellerStandingService standings, @LocalServerPort int port) {
        super(jdbc);
        this.offers = offers;
        this.listing = listing;
        this.buyBox = buyBox;
        this.stock = stock;
        this.standings = standings;
        this.port = port;
    }

    @Test
    void sellersShareAnAtinAndTheBuyBoxFollowsStockPriceAndHealth() {
        String product = atin();
        String variant = atin();
        StubCatalog.live(product, variant, null, LAGOS);

        TestActors.as(TestActors.seller(LAGOS));
        OfferView lagos = offers.create(sellerOffer(variant, "JOL-8-L", 999, 0));
        assertThat(lagos.offer().productAtin()).isEqualTo(product);
        assertThat(lagos.offer().sellerKind()).isEqualTo(SellerKind.SELLER);
        assertThat(lagos.availableQty()).isEqualTo(12);
        assertThat(listing.offers(variant, "USD").winner().view().offer().id()).isEqualTo(lagos.offer().id());

        TestActors.as(TestActors.seller(RETAIL_ORG));
        OfferView retail = offers.create(new CreateOfferCommand(variant, null, null, "AT-JOL-8", null,
                OfferCondition.NEW, 999, 1299L, "USD", 0, Fulfilment.AFROTRANSACT, null, 1, null));
        assertThat(retail.offer().sellerKind()).isEqualTo(SellerKind.PLATFORM);
        assertThat(retail.stockStatus()).isEqualTo(StockStatus.OUT_OF_STOCK);
        assertThat(listing.offers(variant, "USD").winner().view().offer().id())
                .as("no stock yet: retail is not even listed").isEqualTo(lagos.offer().id());
        assertThat(listing.offers(variant, "USD").offerCount()).isEqualTo(1);

        stock.availabilityChanged(UUID.randomUUID(), new StockLevel("AT-JOL-8", "HOU-FC", RETAIL_ORG, 3, 1));
        stock.availabilityChanged(UUID.randomUUID(), new StockLevel("AT-JOL-8", "EWR-FC", RETAIL_ORG, 40, 1));
        BuyBoxView box = listing.offers(variant, "USD");
        assertThat(box.winner().view().offer().id()).as("same price, FBA and faster wins").isEqualTo(
                retail.offer().id());
        assertThat(box.winner().view().availableQty()).isEqualTo(43);
        assertThat(box.winner().badges()).contains(OfferBadge.BUY_BOX_WINNER, OfferBadge.FULFILLED_BY_AFROTRANSACT,
                OfferBadge.SOLD_BY_AFROTRANSACT, OfferBadge.FASTEST_DELIVERY, OfferBadge.LOWEST_PRICE);
        assertThat(box.reasons()).extracting(r -> r.code()).contains("FULFILLED_BY_AFROTRANSACT");
        assertThat(box.otherOffers()).singleElement().satisfies(o -> {
            assertThat(o.view().offer().id()).isEqualTo(lagos.offer().id());
            assertThat(o.badges()).contains(OfferBadge.SHIPS_FROM_SELLER);
            assertThat(o.buyBoxRank()).isEqualTo(2);
        });

        TestActors.as(TestActors.seller(LAGOS));
        offers.update(lagos.offer().id(), new UpdateOfferCommand(null, 500L, null, 0L, null, null, null, null,
                null));
        BuyBoxView afterPriceCut = listing.offers(variant, "USD");
        assertThat(afterPriceCut.winner().view().offer().id()).isEqualTo(lagos.offer().id());
        assertThat(afterPriceCut.winner().landedPriceMinor()).isEqualTo(500);
        assertThat(afterPriceCut.minLandedPriceMinor()).isEqualTo(500);

        TestActors.as(TestActors.staff("offers:write"));
        standings.updatePerformance(LAGOS, new SellerPerformanceCommand(4.9, 0.95, 0.10, false));
        assertThat(listing.offers(variant, "USD").winner().view().offer().id())
                .as("defect rate above threshold: cannot win").isEqualTo(retail.offer().id());
        ListedOffer unhealthy = listing.offer(lagos.offer().id());
        assertThat(unhealthy.notEligible()).containsExactly(Ineligibility.SELLER_HEALTH_BELOW_THRESHOLD);
        standings.updatePerformance(LAGOS, new SellerPerformanceCommand(4.9, 0.95, 0.001, false));
        assertThat(listing.offers(variant, "USD").winner().view().offer().id()).isEqualTo(lagos.offer().id());

        TestActors.reset();
        List<AuditEntry> history = buyBox.history(variant, 50);
        assertThat(history).extracting(AuditEntry::winnerOfferId).containsExactly(lagos.offer().id(),
                retail.offer().id(), lagos.offer().id(), retail.offer().id(), lagos.offer().id());
        assertThat(history.getFirst().trigger()).isEqualTo("seller.performance.updated");
        assertThat(history.getFirst().offers()).hasSize(2);
        assertThat(outboxTypes(variant)).filteredOn("offers.buybox.changed"::equals).hasSizeGreaterThanOrEqualTo(5);
        assertThat(outboxTypes(variant)).contains("offers.offer.created", "offers.offer.updated");

        TestActors.as(TestActors.seller(LAGOS));
        BuyBoxExplanation explained = buyBox.explain(variant, "USD");
        assertThat(explained.evaluations()).allSatisfy(e -> assertThat(e.components()).hasSize(5));
        offers.withdraw(lagos.offer().id());
        assertThat(listing.offers(variant, "USD").winner().view().offer().id()).isEqualTo(retail.offer().id());
        assertThatThrownBy(() -> listing.offer(lagos.offer().id())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void ownershipDuplicatesAndCatalogValidation() {
        String variant = atin();
        StubCatalog.live(atin(), variant, "4006381333931", LAGOS);
        TestActors.as(TestActors.seller(LAGOS));
        OfferView mine = offers.create(sellerOffer(variant, "OWN-1", 999, 0));

        TestActors.as(TestActors.seller(MAMATEE));
        assertThatThrownBy(() -> offers.update(mine.offer().id(), new UpdateOfferCommand(null, 1L, null, null, null,
                null, null, null, null))).isInstanceOf(ForbiddenActionException.class);
        assertThatThrownBy(() -> offers.pause(mine.offer().id())).isInstanceOf(ForbiddenActionException.class);
        OfferView spoof = offers.create(new CreateOfferCommand(variant, null, LAGOS, "SPOOF", null,
                OfferCondition.NEW, 100, null, "USD", 0, Fulfilment.SELLER, 1, 1, HOUSTON));
        assertThat(spoof.offer().sellerOrgId()).as("sellers cannot pick another org: the token org wins")
                .isEqualTo(MAMATEE);
        assertThat(offers.list(null, variant, null, 0, 10).items()).extracting(v -> v.offer().sellerOrgId())
                .containsOnly(MAMATEE);

        OfferView byGtin = offers.create(new CreateOfferCommand(null, "4006381333931", null, "USED-1", null,
                OfferCondition.USED_LIKE_NEW, 700, null, "USD", 0, Fulfilment.SELLER, 1, 1, HOUSTON));
        assertThat(byGtin.offer().atin()).isEqualTo(variant);

        TestActors.as(TestActors.seller(LAGOS));
        assertThatThrownBy(() -> offers.create(sellerOffer(variant, "OWN-2", 999, 0)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("already have");
        assertThatThrownBy(() -> offers.create(sellerOffer(atin(), "OWN-3", 999, 0)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> offers.create(new CreateOfferCommand(variant, null, null, "OWN-4", null,
                OfferCondition.RENEWED, 999, null, "USD", 0, Fulfilment.SELLER, null, 1, null)))
                .isInstanceOfSatisfying(OfferRuleViolation.class,
                        ex -> assertThat(ex.fieldErrors()).containsKeys("quantity", "shipFrom"));

        BuyBoxView box = listing.offers(variant, "USD");
        assertThat(box.offerCount()).isEqualTo(3);
        assertThat(box.otherOffers()).extracting(o -> o.view().offer().condition())
                .as("sorted by landed price; the used offer is listed but cannot win")
                .containsExactly(OfferCondition.USED_LIKE_NEW, OfferCondition.NEW);
    }

    @Test
    void publicEndpointsAndApiReferencePagesAreServed() throws Exception {
        String variant = atin();
        String product = atin();
        StubCatalog.live(product, variant, null, LAGOS);
        TestActors.as(TestActors.seller(LAGOS));
        offers.create(sellerOffer(variant, "HTTP-1", 1299, 0));
        TestActors.reset();

        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<String> box = get(http, "/v1/public/atins/" + variant + "/offers");
        assertThat(box.statusCode()).isEqualTo(200);
        assertThat(box.body()).contains("\"buyBox\"", "\"otherOffers\"", "\"landedPriceMinor\":1299",
                "\"fulfilment\":\"SELLER\"", "BUY_BOX_WINNER");
        assertThat(get(http, "/v1/public/buy-boxes?atins=" + variant + "," + atin()).body()).contains("\"items\"");
        assertThat(get(http, "/v1/public/buy-boxes?by=product&atins=" + product).body())
                .contains("\"atin\":\"" + product + "\"", "\"offerCount\":1", "\"minPriceMinor\":1299");
        for (String path : List.of("/v3/api-docs", "/swagger-ui.html", "/docs", "/redoc")) {
            assertThat(get(http, path).statusCode()).as(path).isEqualTo(200);
        }
        assertThat(get(http, "/v3/api-docs").body()).contains("/v1/coupons/redemptions", "Idempotency-Key");
    }

    private HttpResponse<String> get(HttpClient http, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void sellerStockUpdatesOnlyTheOwnersSellerFulfilledSku() {
        String product = atin();
        String variant = atin();
        StubCatalog.live(product, variant, null, LAGOS);

        TestActors.as(TestActors.seller(LAGOS));
        OfferView mine = offers.create(sellerOffer(variant, "STK-1", 999, 0));
        StockUpdate update = offers.setSellerStock("stk-1", 0, "DAMAGED", null);
        assertThat(update.sku()).isEqualTo("STK-1");
        assertThat(update.offerIds()).containsExactly(mine.offer().id());
        assertThat(offers.get(mine.offer().id()).availableQty()).isZero();
        assertThat(listing.offers(variant, "USD").winner()).as("out of stock: off the buy box").isNull();
        assertThat(offers.setSellerStock("STK-1", 7, "FOUND", null).quantity()).isEqualTo(7);
        assertThat(offers.setSellerStock("STK-1", 7, "FOUND", null).quantity()).as("idempotent").isEqualTo(7);
        assertThat(listing.offers(variant, "USD").winner().view().availableQty()).isEqualTo(7);
        assertThatThrownBy(() -> offers.setSellerStock("STK-1", -1, "OTHER", null))
                .isInstanceOf(OfferRuleViolation.class);

        TestActors.as(TestActors.seller(MAMATEE));
        assertThatThrownBy(() -> offers.setSellerStock("STK-1", 1, "OTHER", null))
                .as("another organisation's SKU is invisible").isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> offers.setSellerStock("STK-1", 1, "OTHER", LAGOS))
                .as("sellers cannot name another organisation").isInstanceOf(NotFoundException.class);

        TestActors.as(TestActors.seller(RETAIL_ORG));
        offers.create(new CreateOfferCommand(variant, null, null, "FBA-STK", null, OfferCondition.NEW, 999, null,
                "USD", 0, Fulfilment.AFROTRANSACT, null, 1, null));
        assertThatThrownBy(() -> offers.setSellerStock("FBA-STK", 3, "OTHER", null))
                .isInstanceOf(ConflictException.class);
        TestActors.reset();

        List<BuyBoxView> cards = listing.productSummaries(List.of(product, atin()), "USD");
        assertThat(cards).hasSize(2);
        assertThat(cards.getFirst().atin()).isEqualTo(product);
        assertThat(cards.getFirst().offerCount()).isEqualTo(1);
        assertThat(cards.getFirst().minPriceMinor()).isEqualTo(999);
        assertThat(cards.getFirst().winner().view().offer().id()).isEqualTo(mine.offer().id());
        assertThat(cards.get(1).winner()).isNull();
        assertThat(cards.get(1).offerCount()).isZero();
    }

    private static CreateOfferCommand sellerOffer(String atin, String sku, long price, long shipping) {
        return new CreateOfferCommand(atin, null, null, sku, null, OfferCondition.NEW, price, null, "USD", shipping,
                Fulfilment.SELLER, 12, 2, HOUSTON);
    }
}
