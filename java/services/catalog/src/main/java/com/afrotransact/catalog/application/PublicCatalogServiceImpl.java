package com.afrotransact.catalog.application;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.sizing.SizeChart;
import com.afrotransact.catalog.port.ListingSuppressionRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.SizeChartRepository;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PublicCatalogServiceImpl implements PublicCatalogService {

    private final ProductRepository products;
    private final ListingSuppressionRepository suppressions;
    private final SizeChartRepository charts;

    PublicCatalogServiceImpl(ProductRepository products, ListingSuppressionRepository suppressions,
                             SizeChartRepository charts) {
        this.products = products;
        this.suppressions = suppressions;
        this.charts = charts;
    }

    @Override
    @Transactional(readOnly = true)
    public Product liveProduct(String atin) {
        return products.findByAtin(parse(atin))
                .filter(this::publiclyVisible)
                .orElseThrow(() -> notFound(atin));
    }

    @Override
    @Transactional(readOnly = true)
    public LiveVariant liveVariant(String variantAtin) {
        Atin wanted = parse(variantAtin);
        Product product = products.findByVariantAtin(wanted)
                .filter(this::publiclyVisible)
                .orElseThrow(() -> notFound(variantAtin));
        Variant variant = product.variants().stream()
                .filter(v -> v.atin().equals(wanted))
                .findFirst()
                .orElseThrow(() -> notFound(variantAtin));
        return new LiveVariant(product, variant);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SizeChart> sizeChartOf(Product product) {
        UUID chartId = product.details().sizeChartId();
        // A chart of another organisation never shows (a product cannot link one, this is belt and braces).
        return chartId == null ? Optional.empty() : charts.findById(chartId)
                .filter(c -> c.ownerOrg().equals(product.owner().orgId()));
    }

    /** LIVE and not hidden by seller-service compliance enforcement (seller.listings.hidden). */
    private boolean publiclyVisible(Product p) {
        return p.status() == ProductStatus.LIVE && !suppressions.isHidden(p.owner().orgId());
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
