package com.afrotransact.catalog.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.afrotransact.catalog.adapter.out.module.InProcessTaxDecisionAuthority;
import com.afrotransact.catalog.adapter.out.module.InProcessTaxResolver;
import com.afrotransact.catalog.adapter.out.tax.DisabledTaxDecisionAuthority;
import com.afrotransact.catalog.adapter.out.tax.HttpTaxDecisionAuthority;
import com.afrotransact.catalog.adapter.out.tax.HttpTaxResolver;
import com.afrotransact.catalog.adapter.out.tax.LocalDatasetTaxResolver;
import com.afrotransact.catalog.application.ImageSettings;
import com.afrotransact.catalog.application.PictureRulesService;
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
import com.afrotransact.catalog.domain.pictures.PictureProfile;
import com.afrotransact.catalog.domain.pictures.PictureRules;
import com.afrotransact.catalog.domain.ReviewContentPolicy;
import com.afrotransact.catalog.domain.barcode.BarcodeEncoder;
import com.afrotransact.catalog.domain.barcode.DefaultBarcodeEncoder;
import com.afrotransact.catalog.domain.barcode.DefaultLabelPolicy;
import com.afrotransact.catalog.domain.barcode.LabelPolicy;
import com.afrotransact.catalog.domain.matching.MatchThresholds;
import com.afrotransact.catalog.domain.prefill.CategoryHintPolicy;
import com.afrotransact.catalog.domain.prefill.DefaultCategoryHintPolicy;
import com.afrotransact.catalog.domain.sizing.DefaultSizeChartPolicy;
import com.afrotransact.catalog.domain.sizing.SizeChartPolicy;
import com.afrotransact.catalog.port.ServiceTokens;
import com.afrotransact.catalog.port.TaxDecisionAuthority;
import com.afrotransact.catalog.port.TaxResolver;
import com.afrotransact.catalog.port.TaxonomySource;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.ModuleCallException;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.taxreturns.api.TaxClassificationApi;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Explicit wiring of framework-free domain policies and configurable collaborators. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProductDataProperties.class)
class CatalogConfiguration {

    /**
     * tax-returns' resolve endpoint (mode {@code http}), its published API inside this JVM (mode {@code in-process}, see
     * {@code at.catalog.taxreturns.mode} and {@code at.catalog.mode}), or the local fake built from the dataset (`at.catalog.tax.resolver=local`).
     */
    @Bean
    TaxResolver taxResolver(RestClient.Builder builder, ServiceTokens tokens, TaxonomySource source,
                            ListableBeanFactory beans, ObjectProvider<TaxClassificationApi> taxApi,
                            ObjectProvider<CallerPrincipals> callers,
                            @Value("${at.catalog.taxreturns.mode:${at.catalog.mode:http}}") String moduleMode,
                            @Value("${at.catalog.tax.resolver:http}") String mode,
                            @Value("${at.catalog.tax.base-url:http://localhost:8111}") String baseUrl,
                            @Value("${at.catalog.tax.timeout:5s}") Duration timeout) {
        if ("local".equals(mode)) {
            return new LocalDatasetTaxResolver(source);
        }
        if (inProcess(moduleMode, beans)) {
            return new InProcessTaxResolver(taxApi, callers);
        }
        return new HttpTaxResolver(builder, tokens, baseUrl, timeout);
    }

    /**
     * The one authority on whether a listing has a clear tax decision: tax-returns' stored decision (what checkout reads).
     * {@code at.catalog.tax.authority=off} switches it off for tests and stacks without tax-returns only; never in dev,
     * uat or prod.
     */
    @Bean
    TaxDecisionAuthority taxDecisionAuthority(RestClient.Builder builder, ServiceTokens tokens,
                                              ListableBeanFactory beans, ObjectProvider<TaxClassificationApi> taxApi,
                                              ObjectProvider<CallerPrincipals> callers,
                                              @Value("${at.catalog.taxreturns.mode:${at.catalog.mode:http}}") String moduleMode,
                                              @Value("${at.catalog.tax.authority:tax-returns}") String mode,
                                              @Value("${at.catalog.tax.base-url:http://localhost:8111}") String baseUrl,
                                              @Value("${at.catalog.tax.authority-timeout:4s}") Duration timeout) {
        if ("off".equals(mode)) {
            LoggerFactory.getLogger(CatalogConfiguration.class).warn("at.catalog.tax.authority=off: listings are NOT "
                    + "checked against tax-returns' stored decisions. Never use this outside tests.");
            return new DisabledTaxDecisionAuthority();
        }
        if (inProcess(moduleMode, beans)) {
            return new InProcessTaxDecisionAuthority(taxApi, callers);
        }
        return new HttpTaxDecisionAuthority(builder, tokens, baseUrl, timeout);
    }

    /**
     * True for {@code at.catalog.mode=in-process}. Fails the start, naming both modules, when tax-returns is not part of this
     * application: the alternative is a first request that fails at run time.
     */
    private static boolean inProcess(String moduleMode, ListableBeanFactory beans) {
        if (ModuleMode.parse(moduleMode, ModuleMode.HTTP) != ModuleMode.IN_PROCESS) {
            return false;
        }
        if (beans.getBeanNamesForType(TaxClassificationApi.class, true, false).length == 0) {
            throw new ModuleCallException("Module 'catalog' is configured at.catalog.mode=in-process but module "
                    + "'tax-returns' is not running in this JVM: enable it (at.modules.tax-returns.enabled=true) or set "
                    + "at.catalog.mode=http");
        }
        return true;
    }

    /**
     * The initial picture-rules profile of this environment (at.catalog.picture-rules.profile: local and dev testing, uat and
     * prod startup). Used only while staff have not saved the setting; changing the line changes the starting point, never a
     * saved value.
     */
    @Bean
    PictureRules pictureRulesDefaults(@Value("${at.catalog.picture-rules.profile:testing}") String profile) {
        return PictureRules.defaults(PictureProfile.parse(profile).orElseThrow(() -> new IllegalStateException(
                "at.catalog.picture-rules.profile must be strict, startup or testing, not '" + profile + "'")));
    }

    @Bean
    ProductDraftPolicy productDraftPolicy(PictureRulesService pictureRules) {
        return new DefaultProductDraftPolicy(pictureRules::current);
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
                                  @Value("${at.catalog.imports.stall-after:2m}") Duration stallAfter,
                                  @Value("${at.catalog.imports.max-rows:5000}") int maxRows,
                                  @Value("${at.catalog.imports.max-feed-rows:50000}") int maxFeedRows,
                                  @Value("${at.catalog.imports.max-uploads-per-day:20}") int maxUploadsPerDay) {
        return new ImportSettings(notifyMinRows, stallAfter, maxRows, maxFeedRows, maxUploadsPerDay);
    }

    /** The numbers of the search-first add-a-product step (docs/engineering/product-matching-without-barcode.md section 4). */
    @Bean
    MatchThresholds matchThresholds(@Value("${at.catalog.matching.show-min:45}") int showMin,
                                    @Value("${at.catalog.matching.likely-min:75}") int likelyMin,
                                    @Value("${at.catalog.matching.max-candidates:6}") int maxCandidates,
                                    @Value("${at.catalog.matching.recall:30}") int recall,
                                    @Value("${at.catalog.matching.min-query-length:3}") int minQueryLength,
                                    @Value("${at.catalog.matching.searches-per-hour:600}") int searchesPerHour,
                                    @Value("${at.catalog.matching.none-decisions-per-day:30}") int noneDecisionsPerDay,
                                    @Value("${at.catalog.matching.open-size-requests:20}") int openSizeRequests) {
        return new MatchThresholds(showMin, likelyMin, maxCandidates, recall, minQueryLength, searchesPerHour,
                noneDecisionsPerDay, openSizeRequests);
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
