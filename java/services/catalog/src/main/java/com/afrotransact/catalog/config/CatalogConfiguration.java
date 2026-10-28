package com.afrotransact.catalog.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.afrotransact.catalog.application.ImportSettings;
import com.afrotransact.catalog.application.LabelSettings;
import com.afrotransact.catalog.application.ListingEnforcementSettings;
import com.afrotransact.catalog.application.OwnerResolver;
import com.afrotransact.catalog.application.ProductDataSettings;
import com.afrotransact.catalog.application.RetailAwareOwnerResolver;
import com.afrotransact.catalog.application.ReviewSettings;
import com.afrotransact.catalog.domain.DefaultProductDraftPolicy;
import com.afrotransact.catalog.domain.DefaultReviewContentPolicy;
import com.afrotransact.catalog.domain.ProductDraftPolicy;
import com.afrotransact.catalog.domain.ReviewContentPolicy;
import com.afrotransact.catalog.domain.barcode.BarcodeEncoder;
import com.afrotransact.catalog.domain.barcode.DefaultBarcodeEncoder;
import com.afrotransact.catalog.domain.barcode.DefaultLabelPolicy;
import com.afrotransact.catalog.domain.barcode.LabelPolicy;
import com.afrotransact.catalog.domain.prefill.CategoryHintPolicy;
import com.afrotransact.catalog.domain.prefill.DefaultCategoryHintPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit wiring of framework-free domain policies and configurable collaborators. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProductDataProperties.class)
class CatalogConfiguration {

    @Bean
    ProductDraftPolicy productDraftPolicy() {
        return new DefaultProductDraftPolicy();
    }

    @Bean
    OwnerResolver ownerResolver(@Value("${at.catalog.retail-org-id}") String retailOrgId) {
        return new RetailAwareOwnerResolver(retailOrgId);
    }

    @Bean
    BarcodeEncoder barcodeEncoder() {
        return new DefaultBarcodeEncoder();
    }

    @Bean
    LabelPolicy labelPolicy(BarcodeEncoder encoder) {
        return new DefaultLabelPolicy(encoder);
    }

    @Bean
    LabelSettings labelSettings(@Value("${at.catalog.retail-org-id}") String retailOrgId) {
        return new LabelSettings(retailOrgId);
    }

    @Bean
    ListingEnforcementSettings listingEnforcementSettings(
            @Value("${at.catalog.listing-enforcement.page-size:500}") int pageSize,
            @Value("${at.catalog.listing-enforcement.stall-after:2m}") Duration stallAfter) {
        return new ListingEnforcementSettings(pageSize, stallAfter);
    }

    @Bean
    ImportSettings importSettings(@Value("${at.catalog.imports.notify-min-rows:100}") int notifyMinRows,
                                  @Value("${at.catalog.imports.stall-after:2m}") Duration stallAfter) {
        return new ImportSettings(notifyMinRows, stallAfter);
    }

    /** Runs bulk-upload imports in the background (one virtual thread per started upload). */
    @Bean(destroyMethod = "close")
    ExecutorService importExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    CategoryHintPolicy categoryHintPolicy() {
        return new DefaultCategoryHintPolicy();
    }

    @Bean
    ProductDataSettings productDataSettings(ProductDataProperties props) {
        return new ProductDataSettings(props.order(), props.cacheTtl(), props.missTtl());
    }

    /** S324 automatic review checks: the built-in profanity list plus the operator's extra words. */
    @Bean
    ReviewContentPolicy reviewContentPolicy(@Value("${at.catalog.reviews.extra-blocked-words:}") String extraWords) {
        return new DefaultReviewContentPolicy(csv(extraWords));
    }

    @Bean
    ReviewSettings reviewSettings(@Value("${at.catalog.reviews.edit-window:30d}") Duration editWindow,
                                  @Value("${at.catalog.reviews.photo-hosts:}") String photoHosts) {
        return new ReviewSettings(editWindow, Set.copyOf(csv(photoHosts).stream()
                .map(h -> h.toLowerCase(Locale.ROOT)).toList()));
    }

    private static List<String> csv(String value) {
        return value == null ? List.of() : Arrays.stream(value.split(",")).map(String::strip)
                .filter(v -> !v.isEmpty()).toList();
    }
}
