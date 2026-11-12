package com.afrotransact.catalog.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.afrotransact.catalog.adapter.out.tax.HttpTaxResolver;
import com.afrotransact.catalog.adapter.out.tax.LocalDatasetTaxResolver;
import com.afrotransact.catalog.application.ImageSettings;
import com.afrotransact.catalog.application.ImportSettings;
import com.afrotransact.catalog.application.LabelSettings;
import com.afrotransact.catalog.application.ListingEnforcementSettings;
import com.afrotransact.catalog.application.OwnerResolver;
import com.afrotransact.catalog.application.ProductDataSettings;
import com.afrotransact.catalog.application.RetailAwareOwnerResolver;
import com.afrotransact.catalog.application.ReviewSettings;
import com.afrotransact.catalog.domain.CatalogPolicy;
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
import com.afrotransact.catalog.domain.sizing.DefaultSizeChartPolicy;
import com.afrotransact.catalog.domain.sizing.SizeChartPolicy;
import com.afrotransact.catalog.port.ServiceTokens;
import com.afrotransact.catalog.port.TaxResolver;
import com.afrotransact.catalog.port.TaxonomySource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Explicit wiring of framework-free domain policies and configurable collaborators. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProductDataProperties.class)
class CatalogConfiguration {

    /** tax-returns' resolve endpoint, or the local fake built from the dataset (`at.catalog.tax.resolver=local`). */
    @Bean
    TaxResolver taxResolver(RestClient.Builder builder, ServiceTokens tokens, TaxonomySource source,
                            @Value("${at.catalog.tax.resolver:http}") String mode,
                            @Value("${at.catalog.tax.base-url:http://localhost:8111}") String baseUrl,
                            @Value("${at.catalog.tax.timeout:5s}") Duration timeout) {
        return "local".equals(mode) ? new LocalDatasetTaxResolver(source)
                : new HttpTaxResolver(builder, tokens, baseUrl, timeout);
    }

    @Bean
    ProductDraftPolicy productDraftPolicy() {
        return new DefaultProductDraftPolicy();
    }

    @Bean
    SizeChartPolicy sizeChartPolicy() {
        return new DefaultSizeChartPolicy();
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

    /** S381-S387: the configured starting values of the thresholds staff edit; the stored copy wins. */
    @Bean
    CatalogPolicy catalogPolicyDefaults(
            @Value("${at.catalog.policy.reserved-prefixes}") List<String> reservedPrefixes,
            @Value("${at.catalog.policy.mpn-attribute-key}") String mpnAttributeKey,
            @Value("${at.catalog.policy.review-sla-business-days}") int reviewSla,
            @Value("${at.catalog.policy.alert-after-business-days}") int alertAfter,
            @Value("${at.catalog.policy.note-max-length}") int noteMaxLength,
            @Value("${at.catalog.policy.known-gs1-brand-min-pages}") int knownBrandMinPages,
            @Value("${at.catalog.policy.exemption-gated-category-codes}") List<String> gatedCodes,
            @Value("${at.catalog.policy.exemption-requires-approval-for-known-brands}") boolean knownBrands) {
        return new CatalogPolicy(reservedPrefixes, mpnAttributeKey, reviewSla, alertAfter, noteMaxLength,
                knownBrandMinPages, gatedCodes, knownBrands, null, null, 0);
    }

    /**
     * The image rules (S390, S392): {@code at.catalog.images.mode} is {@code off} (media is not asked), {@code shadow}
     * (verdicts are recorded, nothing is refused) or {@code enforce}. The hide step for listings that still fail after
     * the grace period is a separate flag and is off until the owner switches it on.
     */
    @Bean
    ImageSettings imageSettings(@Value("${at.catalog.images.mode:off}") String mode,
                                @Value("${at.catalog.images.grace-days:60}") int graceDays,
                                @Value("${at.catalog.images.hide-failing:false}") boolean hideFailing,
                                @Value("${at.catalog.images.rescore-batch:50}") int rescoreBatch,
                                @Value("${at.catalog.images.rescore-every:30s}") Duration rescoreEvery,
                                @Value("${at.catalog.images.recheck-after:30d}") Duration recheckAfter) {
        return new ImageSettings(ImageSettings.Mode.valueOf(mode.trim().toUpperCase(Locale.ROOT)), graceDays, hideFailing,
                rescoreBatch, rescoreEvery, recheckAfter);
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
