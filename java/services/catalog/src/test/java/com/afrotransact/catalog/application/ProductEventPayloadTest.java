package com.afrotransact.catalog.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.Variant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductEventPayloadTest {

    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");

    @Test
    void carriesTheMainImageBrandAndCommunitiesForProductCards() {
        Product p = product(Map.of("brand", " Mama Tee's ", "community", "Nigerian, West African,Nigerian"),
                List.of(image("http://localhost:9000/media/product-image/a.jpg"),
                        image("http://localhost:9000/media/product-image/b.jpg")));

        ProductEventPayload payload = ProductEventPayload.of(p, false);

        assertThat(payload.imageUrl()).isEqualTo("http://localhost:9000/media/product-image/a.jpg");
        assertThat(payload.brand()).isEqualTo("Mama Tee's");
        assertThat(payload.communities()).containsExactly("Nigerian", "West African");
    }

    @Test
    void communitiesAttributeWinsAndMissingValuesAreNullOrEmpty() {
        Product withList = product(Map.of("communities", "Ghanaian", "community", "Nigerian"), List.of());
        Product bare = product(Map.of("brand", "  "), List.of());

        assertThat(ProductEventPayload.of(withList, false).communities()).containsExactly("Ghanaian");
        ProductEventPayload payload = ProductEventPayload.of(bare, false);
        assertThat(payload.imageUrl()).isNull();
        assertThat(payload.brand()).isNull();
        assertThat(payload.communities()).isEmpty();
    }

    private static ProductImage image(String url) {
        return new ProductImage(url, 1200, 1200, 1000, "image/jpeg");
    }

    private static Product product(Map<String, String> attributes, List<ProductImage> images) {
        Variant v = new Variant(UUID.randomUUID(), new Atin("AT0000VAR1"), "SKU-1", null, true,
                Map.of("Net weight", "1 lb"), 899, "USD", 0);
        return new Product(UUID.randomUUID(), new Atin("AT0000PRD1"),
                new ListingOwner(ListingOwnerKind.PLATFORM, "org_afrotransact_retail"), UUID.randomUUID(), null,
                "grocery", "Ofada Rice, Destoned, 5 lb", attributes, images, "Net weight", List.of(v),
                ProductStatus.LIVE, null, NOW, NOW, 0);
    }
}
