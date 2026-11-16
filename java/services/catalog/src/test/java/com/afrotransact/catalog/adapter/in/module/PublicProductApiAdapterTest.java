package com.afrotransact.catalog.adapter.in.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import com.afrotransact.catalog.api.PublicProductApi;
import com.afrotransact.catalog.application.PublicCatalogService;
import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.platform.test.InProcessKit;
import com.afrotransact.platform.web.error.NotFoundException;
import org.junit.jupiter.api.Test;

/** The public product read for Lola: variant first, then the product page, public like the endpoints, empty for what the endpoints 404. */
class PublicProductApiAdapterTest {

    private final PublicCatalogService catalog = mock(PublicCatalogService.class);
    private final PublicProductApi api = new PublicProductApiAdapter(catalog);

    private <T> T asAiGateway(Supplier<T> call) {
        return InProcessCalls.as(InProcessKit.caller("ai-gateway"), "catalog", call);
    }

    private static Product product(Variant... variants) {
        Product p = mock(Product.class);
        Atin atin = mock(Atin.class);
        ListingOwner owner = mock(ListingOwner.class);
        when(atin.value()).thenReturn("ATPRODUCT1");
        when(owner.orgId()).thenReturn("org_malo");
        when(p.atin()).thenReturn(atin);
        when(p.owner()).thenReturn(owner);
        when(p.title()).thenReturn("Garri 2kg");
        when(p.attributes()).thenReturn(Map.of("brand", "Malo"));
        when(p.variants()).thenReturn(List.of(variants));
        return p;
    }

    private static Variant variant(long price, String currency, Map<String, String> options) {
        Variant v = mock(Variant.class);
        when(v.priceMinor()).thenReturn(price);
        when(v.currency()).thenReturn(currency);
        when(v.options()).thenReturn(options);
        return v;
    }

    @Test
    void aVariantAtinGivesTheVariantWithItsOptionsOverTheProductAttributes() {
        Variant v = variant(899, "USD", Map.of("size", "2kg"));
        Product page = product(v);
        when(catalog.liveVariant("ATVARIANT1")).thenReturn(new PublicCatalogService.LiveVariant(page, v));
        assertThat(asAiGateway(() -> api.find("ATVARIANT1"))).contains(new PublicProductApi.PublicItem("ATVARIANT1", "ATPRODUCT1",
                "Garri 2kg", Map.of("brand", "Malo", "size", "2kg"), 899, "USD", "org_malo"));
    }

    @Test
    void aProductAtinFallsBackToThePageAndItsFirstVariant() {
        when(catalog.liveVariant("ATPRODUCT1")).thenThrow(new NotFoundException("catalog.variant-not-found", "no"));
        Product page = product(variant(899, "EUR", Map.of()));
        when(catalog.liveProduct("ATPRODUCT1")).thenReturn(page);
        assertThat(asAiGateway(() -> api.find("ATPRODUCT1"))).get().extracting(PublicProductApi.PublicItem::priceMinor,
                PublicProductApi.PublicItem::currency, PublicProductApi.PublicItem::productAtin).containsExactly(899L, "EUR", "ATPRODUCT1");
    }

    @Test
    void aPageWithoutVariantsHasNoPrice() {
        when(catalog.liveVariant("ATPRODUCT1")).thenThrow(new NotFoundException("catalog.variant-not-found", "no"));
        Product page = product();
        when(catalog.liveProduct("ATPRODUCT1")).thenReturn(page);
        assertThat(asAiGateway(() -> api.find("ATPRODUCT1"))).get().extracting(PublicProductApi.PublicItem::priceMinor,
                PublicProductApi.PublicItem::currency).containsExactly(0L, "USD");
    }

    @Test
    void anUnknownOrNotLiveAtinIsEmptyAsTheEndpointsAre404() {
        when(catalog.liveVariant("NOPE")).thenThrow(new NotFoundException("catalog.variant-not-found", "no"));
        when(catalog.liveProduct("NOPE")).thenThrow(new NotFoundException("catalog.product-not-found", "no"));
        assertThat(asAiGateway(() -> api.find("NOPE"))).isEqualTo(Optional.empty());
    }

    @Test
    void enteredWithoutAnInProcessCallerItFailsClosed() {
        assertThatThrownBy(() -> api.find("ATVARIANT1")).isInstanceOf(IllegalStateException.class);
    }
}
