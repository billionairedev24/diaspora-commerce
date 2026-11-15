package com.afrotransact.orders.adapter.out.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.catalog.api.ProductListingApi;
import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.orders.application.OrdersSettings;
import com.afrotransact.orders.port.ProductFactsClient;
import com.afrotransact.orders.port.TaxQuoteClient;
import com.afrotransact.orders.port.UpstreamFailure;
import com.afrotransact.taxreturns.api.TaxQuoteApi;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** The tax quote and the catalog listing reach their modules in-process and keep the HTTP clients' degradation rules. */
class InProcessCheckoutEdgesTest {

    private static final CallerPrincipal ORDERS = new CallerPrincipal("orders", "orders-service", Set.of("tax:quote"));

    private final TaxQuoteApi tax = Mockito.mock(TaxQuoteApi.class);
    private final ProductListingApi listings = Mockito.mock(ProductListingApi.class);
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final ModuleUpstream upstream;

    InProcessCheckoutEdgesTest() {
        beans.registerSingleton("tax", tax);
        beans.registerSingleton("listings", listings);
        beans.registerSingleton("callers", (CallerPrincipals) module -> ORDERS);
        upstream = new ModuleUpstream(beans.getBeanProvider(CallerPrincipals.class));
    }

    @Test
    void theTaxQuoteCarriesTheShipToTheLinesAndTheShipFrom() {
        Mockito.when(tax.quote(Mockito.any())).thenAnswer(call -> {
            TaxQuoteApi.QuoteRequest r = call.getArgument(0);
            assertThat(r.shipTo().line1()).isNull();
            assertThat(r.orderId()).isEqualTo("o1");
            assertThat(r.lines().get(0).shipFrom().source()).isEqualTo("FULFILMENT_CENTRE");
            return new TaxQuoteApi.Quote("rules", List.of(new TaxQuoteApi.LineTax("l1", 82, 0, "TX", null, "RESOLVED",
                    null, null, null, null, null, "ZIP")));
        });
        TaxQuoteClient client = new InProcessTaxQuoteClient(beans.getBeanProvider(TaxQuoteApi.class), upstream, beans);
        TaxQuoteClient.Quote quote = client.quote(new TaxQuoteClient.Request("USD", "US", "TX", "75001", "Addison", " ",
                List.of(new TaxQuoteClient.Line("l1", "ATIN1", "org", "food", 1, 1000, 0, 0, Map.of(),
                        new TaxQuoteClient.ShipFrom("1 Main", "Newark", "NJ", "07101", "US", "FULFILMENT_CENTRE"))),
                "o1"));
        assertThat(quote.lines()).singleElement().extracting(TaxQuoteClient.LineTax::taxMinor).isEqualTo(82L);
    }

    @Test
    void aMissingTaxScopeIs403() {
        Mockito.when(tax.quote(Mockito.any())).thenThrow(new ModuleApiException(403, "auth.missing-permission", "no"));
        TaxQuoteClient client = new InProcessTaxQuoteClient(beans.getBeanProvider(TaxQuoteApi.class), upstream, beans);
        assertThatThrownBy(() -> client.quote(new TaxQuoteClient.Request("USD", "US", "TX", "75001", "A", List.of(), "o1")))
                .isInstanceOfSatisfying(UpstreamFailure.class, f -> assertThat(f.status()).isEqualTo(403));
    }

    @Test
    void listingsAndFactsAnswerLikeTheHttpClient() {
        Mockito.when(listings.liveListing("V1")).thenReturn(Optional.of(new ProductListingApi.LiveListing("V1", "P1",
                "Egusi", "https://img/1.jpg", "org", "SELLER", "dried_goods", Map.of("perishable", "true"), 1250, "USD",
                Map.of("sold_heated", true, "count", 3, "nested", Map.of("a", 1)))));
        Mockito.when(listings.liveListing("GONE")).thenReturn(Optional.empty());
        OrdersSettings settings = Mockito.mock(OrdersSettings.class);
        Mockito.when(settings.perishableTypes()).thenReturn(Set.of());
        Mockito.when(settings.commissionCategories()).thenReturn(Map.of("dried_goods", "grocery"));
        ProductFactsClient client = new InProcessProductFactsClient(beans.getBeanProvider(ProductListingApi.class),
                upstream, settings, beans);
        assertThat(client.listings(List.of("V1", "GONE"))).containsOnlyKeys("V1");
        ProductFactsClient.Facts facts = client.facts(List.of("V1", "GONE")).get("V1");
        assertThat(facts.perishable()).isTrue();
        assertThat(facts.category()).isEqualTo("grocery");
        assertThat(facts.sellerBehaviour()).containsEntry("sold_heated", true).containsEntry("count", 3L)
                .doesNotContainKey("nested");
        assertThat(BigDecimal.ONE).isNotNull();
    }

    @Test
    void anUnavailableCatalogFailsListingsAndSkipsFacts() {
        Mockito.when(listings.liveListing("V1")).thenThrow(new ModuleApiException(503, "catalog.down", "down"));
        OrdersSettings settings = Mockito.mock(OrdersSettings.class);
        Mockito.when(settings.perishableTypes()).thenReturn(Set.of());
        Mockito.when(settings.commissionCategories()).thenReturn(Map.of());
        ProductFactsClient client = new InProcessProductFactsClient(beans.getBeanProvider(ProductListingApi.class),
                upstream, settings, beans);
        assertThatThrownBy(() -> client.listings(List.of("V1"))).isInstanceOf(UpstreamFailure.class);
        assertThat(client.facts(List.of("V1"))).isEmpty();
    }
}
