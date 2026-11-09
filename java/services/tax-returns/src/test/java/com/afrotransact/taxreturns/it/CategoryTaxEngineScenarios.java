package com.afrotransact.taxreturns.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.taxreturns.application.ClassificationResolveService;
import com.afrotransact.taxreturns.application.ClassificationResolveService.ResolveCommand;
import com.afrotransact.taxreturns.application.ProviderCodeService;
import com.afrotransact.taxreturns.application.TaxProfileService;
import com.afrotransact.taxreturns.application.TaxQuoteCommand;
import com.afrotransact.taxreturns.application.TaxService;
import com.afrotransact.taxreturns.application.TaxSettingsService;
import com.afrotransact.taxreturns.application.TaxShadowService;
import com.afrotransact.taxreturns.application.VariantClassificationService;
import com.afrotransact.taxreturns.application.VariantClassificationService.ApproveCommand;
import com.afrotransact.taxreturns.application.VariantClassificationService.CatalogFactsChanged;
import com.afrotransact.taxreturns.application.VariantClassificationService.Variant;
import com.afrotransact.taxreturns.domain.profile.AuditAction;
import com.afrotransact.taxreturns.domain.profile.AuditEntry;
import com.afrotransact.taxreturns.domain.profile.Outcome;
import com.afrotransact.taxreturns.domain.profile.ProfileStatus;
import com.afrotransact.taxreturns.domain.profile.RecordStatus;
import com.afrotransact.taxreturns.domain.profile.Resolution;
import com.afrotransact.taxreturns.domain.profile.ResolveStatus;
import com.afrotransact.taxreturns.domain.profile.TaxMapFixture;
import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.domain.shadow.ShadowKind;
import com.afrotransact.taxreturns.domain.shadow.ShadowRow;
import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.domain.tax.TaxMethod;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import com.afrotransact.taxreturns.domain.tax.TaxQuote;
import com.afrotransact.taxreturns.port.ShadowQuoteRepository;
import com.afrotransact.taxreturns.port.TaxProfileRepository;
import com.afrotransact.taxreturns.port.VariantClassificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

/**
 * The category tax engine against PostgreSQL: V10 to V12 applied by Flyway, the golden scenarios through the database-backed
 * path, the record lifecycle with real jsonb and optimistic locking, the code map and rule versions, and the shadow store.
 * Run by {@link CategoryTaxEngineIT} (Testcontainers) in CI.
 */
abstract class CategoryTaxEngineScenarios {

    private final ClassificationResolveService resolve;
    private final TaxProfileService profiles;
    private final ProviderCodeService codes;
    private final VariantClassificationService variants;
    private final TaxService tax;
    private final TaxSettingsService settings;
    private final TaxShadowService shadow;
    private final ShadowQuoteRepository shadowRepository;
    private final TaxProfileRepository profileRepository;
    private final VariantClassificationRepository records;
    private final TestActors actors;

    CategoryTaxEngineScenarios(ApplicationContext context) {
        this.resolve = context.getBean(ClassificationResolveService.class);
        this.profiles = context.getBean(TaxProfileService.class);
        this.codes = context.getBean(ProviderCodeService.class);
        this.variants = context.getBean(VariantClassificationService.class);
        this.tax = context.getBean(TaxService.class);
        this.settings = context.getBean(TaxSettingsService.class);
        this.shadow = context.getBean(TaxShadowService.class);
        this.shadowRepository = context.getBean(ShadowQuoteRepository.class);
        this.profileRepository = context.getBean(TaxProfileRepository.class);
        this.records = context.getBean(VariantClassificationRepository.class);
        this.actors = context.getBean(TestActors.class);
    }

    @BeforeEach
    void asStaff() {
        actors.staff();
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyGoldenScenarioResolvesThroughTheDatabaseBackedPath() {
        assertThat(profileRepository.all()).hasSize(73);
        assertThat(profileRepository.all().stream().mapToInt(p -> p.rows().size()).sum()).isEqualTo(105);
        List<Map<String, Object>> scenarios = (List<Map<String, Object>>) TaxMapFixture.read("scenarios.json")
                .get("scenarios");
        for (Map<String, Object> s : scenarios) {
            Map<String, Object> inputs = (Map<String, Object>) s.get("inputs");
            Map<String, Object> expect = (Map<String, Object>) s.get("expect");
            Resolution r = resolve.resolve(new ResolveCommand((String) s.get("categoryCode"), null,
                    (Map<String, Object>) inputs.get("facts"), (Map<String, Object>) inputs.get("sellerBehaviour"),
                    (Map<String, Object>) inputs.get("context"), null, null));
            assertThat(r.outcome().name()).as("scenario %s", s.get("scenario")).isEqualTo(expect.get("outcome"));
            assertThat(r.internalTaxCode()).isEqualTo(expect.get("internalTaxCode"));
            assertThat(r.providerCodes().get("STRIPE")).isEqualTo(expect.get("stripeCode"));
            assertThat(r.status().name()).isEqualTo(expect.get("resolveStatus"));
        }
    }

    @Test
    void aVariantGoesFromCatalogFactsToReviewToApprovalToATaxedQuoteLine() {
        String atin = "ATIT" + Long.toString(System.nanoTime() % 100_000_000L);
        Map<String, Object> taxable = Map.of("is_candy", false, "candy_coated", false, "net_weight_oz", 1,
                "servings_per_package", 1, "individual_serving", true);
        variants.onCatalogFactsChanged(new CatalogFactsChanged(UUID.randomUUID(), "org_a", "nuts-seeds", "nuts-seeds",
                Map.of(), List.of(new Variant(atin, null, taxable, false)), "seller_1", Instant.now()));

        assertThat(variants.list(RecordStatus.PENDING_REVIEW, "nuts-seeds", null, atin, 0, 10).items())
                .extracting(v -> v.atin()).containsExactly(atin);
        TaxQuote before = quote(atin);
        assertThat(before.lines().getFirst().decision().status()).isEqualTo(ResolveStatus.NEEDS_APPROVAL);
        assertThat(before.lines().getFirst().rate()).isNull();

        variants.approve(atin, new ApproveCommand("Label checked", null, null, null));

        TaxQuote after = quote(atin);
        assertThat(after.lines().getFirst().decision().status()).isEqualTo(ResolveStatus.RESOLVED);
        assertThat(after.lines().getFirst().decision().internalTaxCode())
                .isEqualTo("AT.NUTS_SEEDS.PLAIN_SINGLE_OR_UNDER_2_5OZ");
        assertThat(after.lines().getFirst().taxMinor()).isPositive();
        assertThat(after.lines().getFirst().addressPrecision()).isEqualTo("ZIP");
        assertThat(variants.get(atin).audit()).extracting(AuditEntry::action)
                .containsExactly(AuditAction.SUBMITTED, AuditAction.APPROVED);
        assertThat(variants.get(atin).decisions()).hasSize(1);

        Map<String, Object> exempt = Map.of("is_candy", false, "candy_coated", false, "net_weight_oz", 8,
                "servings_per_package", 4, "individual_serving", false);
        variants.onCatalogFactsChanged(new CatalogFactsChanged(UUID.randomUUID(), "org_a", "nuts-seeds", "nuts-seeds",
                Map.of(), List.of(new Variant(atin, null, exempt, false)), "seller_1", Instant.now()));
        assertThat(variants.get(atin).record().status()).isEqualTo(RecordStatus.PENDING_REVIEW);
        assertThat(quote(atin).lines().getFirst().decision().isBlocked()).isTrue();
    }

    private TaxQuote quote(String atin) {
        return tax.quote(new TaxQuoteCommand("USD", new TaxAddress("US", "TX", "77002", "Houston"), List.of(
                new TaxQuoteCommand.Line("1", atin, "org_a", null, null, 1, 10_000, 0, 0))));
    }

    @Test
    void theCodeMapAndTheProfileGateStartNewRuleVersionsAndAreAudited() {
        String before = profileRepository.currentRuleVersion();

        var entry = codes.update("AT.RICE_GRAINS.STANDARD", new ProviderCodeService.Update(TaxProviderId.AVALARA,
                "PF050000", null, "Avalara code taken from the list"));
        String afterMap = profileRepository.currentRuleVersion();
        assertThat(entry.mapVersion()).isEqualTo(2);
        assertThat(afterMap).isNotEqualTo(before).startsWith(before.split("\\+")[0]);
        assertThat(codes.list(TaxProviderId.AVALARA, "rice-grains", true)).isEmpty();

        profiles.changeStatus("toys-games", new TaxProfileService.StatusChange(ProfileStatus.ACTIVE,
                "Owner decided the code policy", null, null));
        assertThat(profileRepository.currentRuleVersion()).isNotEqualTo(afterMap);
        assertThat(profiles.get("toys-games").profile().status()).isEqualTo(ProfileStatus.ACTIVE);
        assertThat(profiles.get("toys-games").stamp().by()).isEqualTo("STAFF:staff_1");
        assertThat(profileRepository.auditOf("toys-games", 5)).hasSize(1);
        Resolution r = resolve.resolve(new ResolveCommand("toys-games", null, Map.of(), Map.of(), Map.of(), null, null));
        assertThat(r.status()).isEqualTo(ResolveStatus.RESOLVED);
        assertThat(r.ruleVersion()).isEqualTo(profileRepository.currentRuleVersion());

        profiles.changeStatus("toys-games", new TaxProfileService.StatusChange(ProfileStatus.REFINE_CODE,
                "Back to the generic gate", null, null));
        assertThat(resolve.resolve(new ResolveCommand("toys-games", null, Map.of(), Map.of(), Map.of(), null, null))
                .status()).isEqualTo(ResolveStatus.REFINE_CODE);
    }

    @Test
    void theShadowStoreRoundTripsAndTheReportGroupsIt() {
        Instant at = Instant.now();
        shadowRepository.saveAll(List.of(
                new ShadowRow(at, "order-it", "1", "ATIT1", TaxProviderId.STRIPE, "TX", "nuts-seeds", "AT.NUTS_SEEDS.X",
                        "txcd_1", ShadowKind.MATCH, Outcome.TAXABLE, new BigDecimal("0.0825"), 825L,
                        Outcome.TAXABLE, new BigDecimal("0.0825"), 825L, null, 0, "USD"),
                new ShadowRow(at, "order-it", "2", "ATIT2", TaxProviderId.STRIPE, "TX", "nuts-seeds", "AT.NUTS_SEEDS.X",
                        "txcd_1", ShadowKind.OUTCOME, Outcome.EXEMPT, null, 0L, Outcome.TAXABLE,
                        new BigDecimal("0.0625"), 625L, null, 625, "USD")));

        TaxShadowService.Report r = shadow.report(LocalDate.now(ZoneOffset.UTC),
                LocalDate.now(ZoneOffset.UTC), TaxProviderId.STRIPE, true, 0, 50);

        assertThat(r.totals().compared()).isGreaterThanOrEqualTo(2);
        assertThat(r.totals().differing()).isGreaterThanOrEqualTo(1);
        assertThat(r.differences()).anySatisfy(d -> assertThat(d.kind()).isEqualTo(ShadowKind.OUTCOME));
        assertThat(r.byCode()).anySatisfy(g -> {
            assertThat(g.key()).isEqualTo("AT.NUTS_SEEDS.X");
            assertThat(g.providerCode()).isEqualTo("txcd_1");
        });
        assertThat(r.byState()).extracting(g -> g.key()).contains("TX");
    }

    @Test
    void shadowAndMethodAreValidatedAndRecorded() {
        TaxSettingsService.TaxSettings on = settings.change(TaxMethod.RULES, TaxProviderId.STRIPE,
                "Shadowing Stripe on real orders");
        assertThat(on.shadowProvider()).isEqualTo(TaxProviderId.STRIPE);

        assertThatThrownBy(() -> settings.change(TaxMethod.RULES, TaxProviderId.AVALARA, "Avalara is not set up here"))
                .isInstanceOf(ReturnRuleViolation.class).hasMessageContaining("Avalara cannot be used");
        assertThatThrownBy(() -> settings.change(TaxMethod.STRIPE_TAX, TaxProviderId.STRIPE, "Shadow the method itself"))
                .isInstanceOf(ReturnRuleViolation.class);

        assertThat(settings.change(TaxMethod.RULES, null, "Comparison finished, switching shadow off").shadowProvider())
                .isNull();
    }
}
