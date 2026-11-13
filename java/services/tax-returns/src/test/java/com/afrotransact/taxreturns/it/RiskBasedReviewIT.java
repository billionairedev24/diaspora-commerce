package com.afrotransact.taxreturns.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.platform.test.PostgresTestContainer;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.taxreturns.application.TaxQuoteCommand;
import com.afrotransact.taxreturns.application.TaxReviewSettingsService;
import com.afrotransact.taxreturns.application.TaxReviewSettingsService.ReviewSettingsView;
import com.afrotransact.taxreturns.application.TaxService;
import com.afrotransact.taxreturns.application.VariantClassificationService;
import com.afrotransact.taxreturns.application.VariantClassificationService.ApproveCommand;
import com.afrotransact.taxreturns.application.VariantClassificationService.CatalogFactsChanged;
import com.afrotransact.taxreturns.application.VariantClassificationService.ClassificationView;
import com.afrotransact.taxreturns.application.VariantClassificationService.ReclassifyCommand;
import com.afrotransact.taxreturns.application.VariantClassificationService.Variant;
import com.afrotransact.taxreturns.domain.profile.AuditAction;
import com.afrotransact.taxreturns.domain.profile.AuditEntry;
import com.afrotransact.taxreturns.domain.profile.RecordStatus;
import com.afrotransact.taxreturns.domain.profile.ResolveStatus;
import com.afrotransact.taxreturns.domain.profile.ReviewMode;
import com.afrotransact.taxreturns.domain.profile.ReviewSettings;
import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.domain.tax.TaxLineResult;
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
 * Risk-based tax review against PostgreSQL (V13 applied by Flyway, real jsonb and optimistic locking): the setting is
 * RISK_BASED by default (the rules approve a clean record, exceptions wait), a switch from ALL approves the waiting
 * records the rules decide, RISK_BASED samples for audit, a confirm or reclassify of an audit item works, and checkout still blocks any line without a READY RESOLVED stored decision.
 * {@code AT_IT_POSTGRES_URL=jdbc:postgresql://…} runs it on that PostgreSQL instead of a container.
 */
@SpringBootTest(properties = {"at.events.relay-enabled=false", "at.consumers.enabled=false",
    "at.taxreturns.jobs.enabled=false", "spring.kafka.bootstrap-servers=localhost:1",
    "at.taxreturns.tax.stripe.secret-key=sk_test_integration"})
@ActiveProfiles("local")
@Import(StubUpstreams.class)
class RiskBasedReviewIT {

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

    private final TaxReviewSettingsService settings;
    private final VariantClassificationService variants;
    private final TaxService tax;
    private final TestActors actors;

    @Autowired
    RiskBasedReviewIT(TaxReviewSettingsService settings, VariantClassificationService variants, TaxService tax,
                      TestActors actors) {
        this.settings = settings;
        this.variants = variants;
        this.tax = tax;
        this.actors = actors;
    }

    @BeforeEach
    @AfterEach
    void backToTheDefault() {
        actors.staff();
        if (!settings.current().settings().equals(ReviewSettings.defaults())) {
            settings.change(ReviewMode.RISK_BASED, ReviewSettings.DEFAULT_ALWAYS_REVIEW,
                    ReviewSettings.DEFAULT_AUDIT_PERCENT, "Test cleanup: back to the defaults");
        }
    }

    private static String atin() {
        return "ATRB" + Long.toString(System.nanoTime() % 100_000_000L);
    }

    private void submit(String category, String atin, Map<String, Object> facts) {
        variants.onCatalogFactsChanged(new CatalogFactsChanged(UUID.randomUUID(), "org_a", category, category, Map.of(),
                List.of(new Variant(atin, null, facts, false)), "seller_1", Instant.now()));
    }

    private TaxLineResult line(String atin) {
        return tax.quote(new TaxQuoteCommand("USD", new TaxAddress("US", "TX", "77002", "Houston"), List.of(
                new TaxQuoteCommand.Line("1", atin, "org_a", null, null, 1, 10_000, 0, 0))))
                .lines().getFirst();
    }

    @Test
    void theModeIsRiskBasedByDefaultAndOnlyExceptionsWaitForAReviewer() {
        ReviewSettingsView v = settings.current();
        assertThat(v.settings().mode()).isEqualTo(ReviewMode.RISK_BASED);
        assertThat(v.settings().auditPercent()).isEqualTo(5);
        assertThat(v.settings().alwaysReview()).containsExactlyInAnyOrder("dietary-supplements", "otc-medicines",
                "medicated-skincare", "medical-devices", "baby-food-formula", "gift-cards");

        String plain = atin();
        String regulated = atin();
        submit("haircare", plain, Map.of());
        submit("gift-cards", regulated, Map.of());

        assertThat(variants.get(plain).record().status()).isEqualTo(RecordStatus.APPROVED);
        assertThat(line(plain).decision().status()).isEqualTo(ResolveStatus.RESOLVED);
        assertThat(variants.get(regulated).record().status()).isEqualTo(RecordStatus.PENDING_REVIEW);
        assertThat(variants.get(regulated).reviewReason()).startsWith("Regulated category:");
        assertThat(line(regulated).decision().isBlocked()).as("not approved, so checkout blocks the line").isTrue();
    }

    @Test
    void switchingToRiskBasedApprovesTheRecordsThatWaitedUnderAllAndLeavesTheExceptions() {
        settings.change(ReviewMode.ALL, null, 0, "Review everything for this scenario");
        String plain = atin();
        String regulated = atin();
        submit("haircare", plain, Map.of());
        submit("gift-cards", regulated, Map.of());
        assertThat(variants.get(plain).record().status()).isEqualTo(RecordStatus.PENDING_REVIEW);

        settings.change(ReviewMode.RISK_BASED, null, 0, "Rules decide, a person reviews the exceptions");

        ClassificationView approved = variants.get(plain);
        assertThat(approved.record().status()).isEqualTo(RecordStatus.APPROVED);
        assertThat(approved.audit().getLast().actor()).isEqualTo("auto");
        assertThat(approved.audit().getLast().reason()).startsWith("Approved by the risk-based rule after waiting");
        assertThat(line(plain).decision().status()).isEqualTo(ResolveStatus.RESOLVED);
        assertThat(variants.get(regulated).record().status()).isEqualTo(RecordStatus.PENDING_REVIEW);
    }

    @Test
    void theSettingNeedsTheAdminPermissionAReasonAndIsHistoryBacked() {
        actors.seller("org_a", "tax:read");
        assertThatThrownBy(() -> settings.change(ReviewMode.RISK_BASED, null, null, "Tax professional agreed"))
                .isInstanceOf(ForbiddenActionException.class);
        actors.staff();
        assertThatThrownBy(() -> settings.change(ReviewMode.RISK_BASED, null, null, "short"))
                .isInstanceOf(ReturnRuleViolation.class);
        assertThatThrownBy(() -> settings.change(ReviewMode.RISK_BASED, List.of("no-such-category"), null,
                "Tax professional agreed")).isInstanceOf(ReturnRuleViolation.class);

        List<String> longList = List.of("baby-food-formula", "dietary-supplements", "gift-cards", "medical-devices",
                "medicated-skincare", "otc-medicines", "haircare", "skincare-cosmetics", "perfumes-fragrances",
                "adult-clothing-footwear", "childrens-clothing", "jewelry-accessories", "bedding-linens");
        ReviewSettingsView on = settings.change(ReviewMode.RISK_BASED, longList, 12, "Tax professional agreed, ref TX-1");

        assertThat(on.settings().mode()).isEqualTo(ReviewMode.RISK_BASED);
        assertThat(on.settings().alwaysReview()).containsExactlyInAnyOrderElementsOf(longList);
        assertThat(on.settings().auditPercent()).isEqualTo(12);
        assertThat(on.changedBy()).isEqualTo("staff_1");
        assertThat(on.history().getFirst().reason()).isEqualTo("Tax professional agreed, ref TX-1");
        assertThat(on.history().getFirst().to().mode()).isEqualTo(ReviewMode.RISK_BASED);
        assertThat(settings.current().settings()).isEqualTo(on.settings());
    }

    @Test
    void riskBasedApprovesByRuleSamplesForAuditAndTheCheckoutLineIsResolved() {
        settings.change(ReviewMode.RISK_BASED, null, 100, "Tax professional agreed, ref TX-2");
        String atin = atin();

        submit("haircare", atin, Map.of());

        ClassificationView v = variants.get(atin);
        assertThat(v.record().status()).isEqualTo(RecordStatus.APPROVED);
        assertThat(v.record().classifiedBy()).isEqualTo("auto");
        assertThat(v.record().reviewedBy()).isEqualTo("auto");
        assertThat(v.record().ruleVersion()).isNotBlank();
        assertThat(v.record().auditItem()).isTrue();
        assertThat(v.audit()).extracting(AuditEntry::action).containsExactly(AuditAction.SUBMITTED, AuditAction.APPROVED,
                AuditAction.AUDIT_SAMPLED);
        assertThat(v.audit().get(1).actor()).isEqualTo("auto");
        assertThat(v.audit().get(1).reason()).contains("ACTIVE").contains("always-review list");
        assertThat(v.audit().get(1).after()).containsKey("facts").containsEntry("classifiedBy", "auto");
        assertThat(line(atin).decision().status()).isEqualTo(ResolveStatus.RESOLVED);
        assertThat(line(atin).taxMinor()).isPositive();
        assertThat(variants.list(null, null, null, atin, true, 0, 10).items()).extracting(r -> r.atin())
                .containsExactly(atin);
    }

    @Test
    void confirmingTheAuditItemClosesItAndKeepsTheItemLive() {
        settings.change(ReviewMode.RISK_BASED, null, 100, "Tax professional agreed, ref TX-3");
        String atin = atin();
        submit("haircare", atin, Map.of());

        ClassificationView confirmed = variants.approve(atin, new ApproveCommand("Checked the label", null, null, null));

        assertThat(confirmed.record().auditItem()).isFalse();
        assertThat(confirmed.record().status()).isEqualTo(RecordStatus.APPROVED);
        assertThat(confirmed.audit().getLast().action()).isEqualTo(AuditAction.AUDIT_CONFIRMED);
        assertThat(variants.list(null, null, null, atin, true, 0, 10).items()).isEmpty();
        assertThat(line(atin).decision().status()).isEqualTo(ResolveStatus.RESOLVED);
    }

    @Test
    void reclassifyingTheAuditItemReClosesTheGateAndTheCheckoutLineIsBlocked() {
        settings.change(ReviewMode.RISK_BASED, null, 100, "Tax professional agreed, ref TX-4");
        String atin = atin();
        submit("haircare", atin, Map.of());
        assertThat(line(atin).decision().status()).isEqualTo(ResolveStatus.RESOLVED);

        ClassificationView moved = variants.reclassify(atin, new ReclassifyCommand("skincare-cosmetics", null,
                "This is a skincare product"));

        assertThat(moved.record().status()).isEqualTo(RecordStatus.PENDING_REVIEW);
        assertThat(moved.record().auditItem()).isFalse();
        assertThat(variants.list(null, null, null, atin, true, 0, 10).items()).isEmpty();
        TaxLineResult blocked = line(atin);
        assertThat(blocked.decision().isBlocked()).isTrue();
        assertThat(blocked.rate()).as("never a default rate").isNull();
    }

    @Test
    void gatedAndAlwaysReviewCategoriesStayInTheQueueAndAVariantWithoutARecordIsBlocked() {
        settings.change(ReviewMode.RISK_BASED, null, 0, "Tax professional agreed, ref TX-5");
        String gated = atin();
        String regulated = atin();

        submit("toys-games", gated, Map.of());
        submit("baby-food-formula", regulated, Map.of());

        assertThat(variants.get(gated).record().status()).isEqualTo(RecordStatus.PENDING_REVIEW);
        assertThat(variants.get(regulated).record().status()).isEqualTo(RecordStatus.PENDING_REVIEW);
        assertThat(line(gated).decision().isBlocked()).isTrue();
        assertThat(line(regulated).decision().isBlocked()).isTrue();
        assertThat(line("ATRBNOREC" + System.nanoTime() % 1000).decision().isBlocked()).isTrue();
    }
}
