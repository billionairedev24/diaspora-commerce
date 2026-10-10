package com.afrotransact.catalog.application;

import java.util.Locale;

import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PublicCatalogServiceImpl implements PublicCatalogService {

    private final ProductRepository products;

    PublicCatalogServiceImpl(ProductRepository products) {
        this.products = products;
    }

    @Override
    @Transactional(readOnly = true)
    public Product liveProduct(String atin) {
        return products.findByAtin(parse(atin))
                .filter(p -> p.status() == ProductStatus.LIVE)
                .orElseThrow(() -> notFound(atin));
    }

    @Override
    @Transactional(readOnly = true)
    public LiveVariant liveVariant(String variantAtin) {
        Atin wanted = parse(variantAtin);
        Product product = products.findByVariantAtin(wanted)
                .filter(p -> p.status() == ProductStatus.LIVE)
                .orElseThrow(() -> notFound(variantAtin));
        Variant variant = product.variants().stream()
                .filter(v -> v.atin().equals(wanted))
                .findFirst()
                .orElseThrow(() -> notFound(variantAtin));
        return new LiveVariant(product, variant);
    }

    private static Atin parse(String raw) {
        try {
            return new Atin(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw notFound(raw);
        }
    }

    private static NotFoundException notFound(String atin) {
        return new NotFoundException("catalog.product-not-found", "No live product " + atin);
    }
}
