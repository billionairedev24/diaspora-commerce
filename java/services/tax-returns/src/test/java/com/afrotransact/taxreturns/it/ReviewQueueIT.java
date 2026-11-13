package com.afrotransact.taxreturns.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.platform.test.PostgresTestContainer;
import com.afrotransact.taxreturns.application.TaxReviewSettingsService;
import com.afrotransact.taxreturns.application.VariantClassificationService;
import com.afrotransact.taxreturns.application.VariantClassificationService.CatalogFactsChanged;
import com.afrotransact.taxreturns.application.VariantClassificationService.ReviewList;
import com.afrotransact.taxreturns.application.VariantClassificationService.Variant;
import com.afrotransact.taxreturns.domain.profile.RecordStatus;
import com.afrotransact.taxreturns.domain.profile.ReviewMode;
import com.afrotransact.taxreturns.domain.profile.ReviewReason;
import com.afrotransact.taxreturns.domain.profile.ReviewSettings;
import com.afrotransact.taxreturns.domain.profile.VariantClassification;
import com.afrotransact.taxreturns.port.VariantClassificationRepository.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The review queue's server-side search on PostgreSQL (real jsonb): a text search by title, ATIN or seller, a filter by
 * the reason a person is needed, a sort by title, and the sellers of every row (the list used to carry none, which left
 * the staff table's Seller column blank). {@code AT_IT_POSTGRES_URL=jdbc:postgresql://…} runs it on that PostgreSQL.
 */
@SpringBootTest(properties = {"at.events.relay-enabled=false", "at.consumers.enabled=false",
    "at.taxreturns.jobs.enabled=false", "spring.kafka.bootstrap-servers=localhost:1",
    "at.taxreturns.tax.stripe.secret-key=sk_test_integration"})
@ActiveProfiles("local")
@Import(StubUpstreams.class)
class ReviewQueueIT {

    static final String EXTERNAL_DB = System.getenv("AT_IT_POSTGRES_URL");
    static final PostgreSQLContainer POSTGRES = EXTERNAL_DB == null ? PostgresTestContainer.create() : null;

    static {
        if (POSTGRES != null) {
            POSTGRES.start();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> EXTERNAL_DB != null ? EXTERNAL_DB : POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.username", () -> EXTERNAL_DB != null
                ? System.getenv().getOrDefault("AT_IT_POSTGRES_USER", "postgres") : POSTGRES.getUsername());
        registry.add("spring.datasource.password", () -> EXTERNAL_DB != null
                ? System.getenv().getOrDefault("AT_IT_POSTGRES_PASSWORD", "") : POSTGRES.getPassword());
    }

    private final VariantClassificationService variants;
    private final TaxReviewSettingsService settings;
    private final TestActors actors;

    @Autowired
    ReviewQueueIT(VariantClassificationService variants, TaxReviewSettingsService settings, TestActors actors) {
        this.variants = variants;
        this.settings = settings;
        this.actors = actors;
    }

    /** Every record waits for a person in these tests (the default lets the rules approve what they can decide). */
    @BeforeEach
    void reviewEverything() {
        actors.staff();
        settings.change(ReviewMode.ALL, null, 0, "Review everything for the review queue scenarios");
    }

    @AfterEach
    void backToTheDefault() {
        actors.staff();
        settings.change(ReviewMode.RISK_BASED, ReviewSettings.DEFAULT_ALWAYS_REVIEW, ReviewSettings.DEFAULT_AUDIT_PERCENT,
                "Test cleanup: back to the defaults");
    }

    private void submit(String org, String category, String atin, String title, Map<String, Object> facts) {
        variants.onCatalogFactsChanged(new CatalogFactsChanged(UUID.randomUUID(), org, category, category, Map.of(),
                List.of(new Variant(atin, null, facts, false)), "seller_1", Instant.now(), title));
    }

    private ReviewList search(Filter filter) {
        return variants.search(filter, 0, 50);
    }

    @Test
    void everyRowNamesItsSellerAndTheReasonAPersonIsNeeded() {
        String org = "org_q" + Long.toString(System.nanoTime() % 1_000_000L);
        String stamp = Long.toString(System.nanoTime() % 100_000_000L);
        submit(org, "haircare", "ATQ1" + stamp, "Shea butter pot " + stamp, Map.of());
        submit(org, "nuts-seeds", "ATQ2" + stamp, "Zesty cashews " + stamp, Map.of("is_candy", false));

        ReviewList all = search(new Filter(null, null, org, null));
        ReviewList missing = search(new Filter(null, null, org, null, false, ReviewReason.MISSING_FACT, null, null, false));
        ReviewList waiting = search(new Filter(null, null, org, null, false, ReviewReason.NEW_LISTING, null, null, false));

        assertThat(all.items()).extracting(VariantClassification::atin).containsExactlyInAnyOrder("ATQ1" + stamp, "ATQ2" + stamp);
        assertThat(all.sellers()).containsEntry("ATQ1" + stamp, List.of(org)).containsEntry("ATQ2" + stamp, List.of(org));
        assertThat(missing.items()).extracting(VariantClassification::atin).containsExactly("ATQ2" + stamp);
        assertThat(missing.items().getFirst().status()).isEqualTo(RecordStatus.MISSING_FACTS);
        assertThat(waiting.items()).extracting(VariantClassification::atin).containsExactly("ATQ1" + stamp);
    }

    @Test
    void theSearchFindsATitleAnAtinOrASellerByNameAndSortsByTitle() {
        String org = "org_s" + Long.toString(System.nanoTime() % 1_000_000L);
        String stamp = Long.toString(System.nanoTime() % 100_000_000L);
        submit(org, "haircare", "ATS1" + stamp, "Beta oil " + stamp, Map.of());
        submit(org, "haircare", "ATS2" + stamp, "Alpha balm " + stamp, Map.of());

        assertThat(search(new Filter(null, null, null, "alpha balm " + stamp)).items())
                .extracting(VariantClassification::atin).containsExactly("ATS2" + stamp);
        assertThat(search(new Filter(null, null, null, "ats1" + stamp)).items())
                .extracting(VariantClassification::atin).containsExactly("ATS1" + stamp);
        // The seller's name lives in another service: the caller sends the ids of the organisations whose name matched.
        ReviewList bySeller = search(new Filter(null, null, null, "Mama Nkechi", false, null, List.of(org), "title", false));
        assertThat(bySeller.items()).extracting(VariantClassification::title).containsExactly("Alpha balm " + stamp,
                "Beta oil " + stamp);
        assertThat(search(new Filter(null, null, org, null, false, null, null, "title", true)).items())
                .extracting(VariantClassification::atin).containsExactly("ATS1" + stamp, "ATS2" + stamp);
    }
}
