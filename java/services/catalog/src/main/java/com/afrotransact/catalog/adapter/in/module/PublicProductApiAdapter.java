package com.afrotransact.catalog.adapter.in.module;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.catalog.api.PublicProductApi;
import com.afrotransact.catalog.application.PublicCatalogService;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link PublicProductApi}: the anonymous public reads {@code GET /v1/public/variants/{atin}} then
 * {@code GET /v1/public/products/{atin}} that the AI shopping agent makes, through {@link PublicCatalogService}. No scope over HTTP,
 * none here; a caller principal is still required so that no code path reaches it unnamed. Unknown or not-live ATINs are empty (404).
 */
@Component
class PublicProductApiAdapter implements PublicProductApi {

    private final PublicCatalogService catalog;

    PublicProductApiAdapter(PublicCatalogService catalog) {
        this.catalog = catalog;
    }

    @Override
    public Optional<PublicItem> find(String atin) {
        InProcessCalls.require();
        try {
            PublicCatalogService.LiveVariant live = catalog.liveVariant(atin);
            return Optional.of(item(atin, live.product(), live.variant()));
        } catch (NotFoundException notAVariant) {
            // fall through to the product page, as the HTTP adapter asks the products endpoint next
        }
        try {
            Product product = catalog.liveProduct(atin);
            return Optional.of(item(atin, product, product.variants().isEmpty() ? null : product.variants().get(0)));
        } catch (NotFoundException unknown) {
            return Optional.empty();
        }
    }

    private static PublicItem item(String atin, Product product, Variant variant) {
        Map<String, String> attributes = new LinkedHashMap<>(product.attributes());
        if (variant != null) {
            attributes.putAll(variant.options());
        }
        return new PublicItem(atin, product.atin().value(), product.title(), attributes,
                variant == null ? 0 : variant.priceMinor(), variant == null ? "USD" : variant.currency(),
                product.owner().orgId());
    }
}
