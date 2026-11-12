package com.afrotransact.catalog.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.adapter.in.web.ProductDtos.VariantResponse;
import com.afrotransact.catalog.adapter.in.web.PublicProductController.PublicProductResponse;
import com.afrotransact.catalog.adapter.in.web.PublicProductController.PublicVariantResponse;
import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.ListingDetails;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.tax.TaxDecision;
import com.afrotransact.catalog.domain.tax.TaxStatus;
import com.afrotransact.catalog.domain.tax.VariantTax;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What {@code GET /v1/public/variants/{atin}} answers is what tax-returns (on-the-fly lookup) and orders (the seller's
 * behaviour for the tax quote) read: {@code product.categoryCode}, {@code product.listing.sellerBehaviour},
 * {@code product.sellerOrgId} and {@code variant.taxFacts}. The same fixture is read by their tests
 * ({@code contracts/examples/catalog-public-variant.json}); every field in it must be in catalog's real answer.
 */
class PublicVariantContractTest {

    private static final Path FIXTURE = Path.of("..", "..", "..", "contracts", "examples",
            "catalog-public-variant.json");
    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void thePublicVariantCarriesWhatTaxReturnsAndOrdersRead() throws IOException {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("net_weight_oz", new BigDecimal("8.5"));
        facts.put("servings_per_package", 4);
        facts.put("label_type", "NUTRITION_FACTS");
        Variant variant = new Variant(UUID.randomUUID(), new Atin("AT0000VAR1"), "AMK-1", null, true,
                Map.of("Size", "8.5 oz"), 899, "USD", 5, null, List.of(), false,
                new VariantTax(facts, TaxStatus.READY, List.of(), new TaxDecision("EXEMPT", "AT.X", "S001", "0%", "v1", "r")));
        Map<String, Object> behaviour = new LinkedHashMap<>();
        behaviour.put("sold_heated", false);
        behaviour.put("utensils_provided", true);
        behaviour.put("heated_by_seller", false);
        ListingDetails details = ListingDetails.DEFAULT.withSellerBehaviour(behaviour);
        Product product = new Product(UUID.randomUUID(), new Atin("AT0000PRD1"),
                new ListingOwner(ListingOwnerKind.SELLER, "org_amaka_kitchen"), UUID.randomUUID(), null, "nuts-seeds",
                "Roasted cashews", Map.of(), List.of(), "Size", List.of(variant), ProductStatus.LIVE, null, NOW, NOW,
                0, false, false, details);

        PublicVariantResponse answer = new PublicVariantResponse(
                PublicProductResponse.of(product, null, null, "nuts-seeds"), VariantResponse.of(variant));
        JsonNode actual = json.readTree(json.writeValueAsString(answer));
        JsonNode expected = json.readTree(Files.readString(FIXTURE));

        assertThat(missing("", expected, actual)).as("fixture fields missing from catalog's answer").isEmpty();
    }

    @Test
    void aProductWithoutATaxProfileHasANullCategoryCodeNotAGuess() throws IOException {
        Variant variant = new Variant(UUID.randomUUID(), new Atin("AT0000VAR1"), "AMK-1", null, true, Map.of(), 899,
                "USD", 5);
        Product product = new Product(UUID.randomUUID(), new Atin("AT0000PRD1"),
                new ListingOwner(ListingOwnerKind.SELLER, "org_amaka_kitchen"), UUID.randomUUID(), null, "nuts-seeds",
                "Roasted cashews", Map.of(), List.of(), "Size", List.of(variant), ProductStatus.LIVE, null, NOW, NOW, 0);

        JsonNode actual = json.readTree(json.writeValueAsString(PublicProductResponse.of(product, null, null, null)));

        assertThat(actual.path("categoryCode").isNull()).isTrue();
        assertThat(actual.path("listing").path("sellerBehaviour").isObject()).isTrue();
        assertThat(actual.path("listing").path("sellerBehaviour")).isEmpty();
    }

    /** Paths of {@code expected} that {@code actual} lacks or holds a different value for. */
    private static List<String> missing(String path, JsonNode expected, JsonNode actual) {
        List<String> out = new ArrayList<>();
        if (expected.isObject()) {
            expected.properties().forEach(e -> {
                JsonNode got = actual.path(e.getKey());
                if (got.isMissingNode()) {
                    out.add(path + "/" + e.getKey());
                } else {
                    out.addAll(missing(path + "/" + e.getKey(), e.getValue(), got));
                }
            });
        } else if (expected.isNumber() && actual.isNumber()) {
            if (expected.decimalValue().compareTo(actual.decimalValue()) != 0) {
                out.add(path + " = " + actual + " (expected " + expected + ")");
            }
        } else if (!expected.equals(actual)) {
            out.add(path + " = " + actual + " (expected " + expected + ")");
        }
        return out;
    }
}
