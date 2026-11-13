package com.afrotransact.taxreturns.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.taxreturns.application.VariantClassificationService.ApproveCommand;
import com.afrotransact.taxreturns.application.VariantClassificationService.CatalogFactsChanged;
import com.afrotransact.taxreturns.application.VariantClassificationService.ClassificationView;
import com.afrotransact.taxreturns.application.VariantClassificationService.ReclassifyCommand;
import com.afrotransact.taxreturns.application.VariantClassificationService.Variant;
import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.domain.profile.ApprovalPolicy;
import com.afrotransact.taxreturns.domain.profile.AuditAction;
import com.afrotransact.taxreturns.domain.profile.AuditEntry;
import com.afrotransact.taxreturns.domain.profile.ProfileStatus;
import com.afrotransact.taxreturns.domain.profile.RecordStatus;
import com.afrotransact.taxreturns.domain.profile.ReviewReason;
import com.afrotransact.taxreturns.domain.profile.VariantClassification;
import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.port.TaxTelemetry;
import com.afrotransact.taxreturns.port.VariantClassificationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The record lifecycle: created from catalog facts, queued, approved, made stale by a changed outcome, reclassified. */
class VariantClassificationServiceImplTest {

    private static final UUID PRODUCT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Map<String, Object> EXEMPT_NUTS = Map.of("is_candy", false, "candy_coated", false,
            "net_weight_oz", 8, "servings_per_package", 4, "individual_serving", false);
    private static final Map<String, Object> TAXABLE_NUTS = Map.of("is_candy", false, "candy_coated", false,
            "net_weight_oz", 1, "servings_per_package", 1, "individual_serving", true);

    private final TestRules rules = new TestRules();
    private final InMemoryClassifications records = new InMemoryClassifications();
    private final DomainEventPublisher publisher = mock(DomainEventPublisher.class);
    private final TaxTelemetry telemetry = mock(TaxTelemetry.class);
    private final InMemoryReviewSettings reviewSettings = InMemoryReviewSettings.reviewingEverything();

    private VariantClassificationService service(ApprovalPolicy policy) {
        TaxReturnsProperties props = TestRules.props(new TaxReturnsProperties.Classification(null, policy, false, null));
        return new VariantClassificationServiceImpl(records, rules.engine, new ListingDecisions(records),
                new ClassificationEvents(publisher, telemetry), props,
                reviewSettings, TestActors.staff("tax:classify-admin", "tax:read"), TestRules.CLOCK);
    }

    private static CatalogFactsChanged event(String category, String atin, Map<String, Object> facts,
                                             Map<String, Object> behaviour, boolean evidenceChanged) {
        return new CatalogFactsChanged(PRODUCT, "org_a", category, category, behaviour,
                List.of(new Variant(atin, null, facts, evidenceChanged)), "seller_1", TestRules.CLOCK.instant());
    }

    @Test
    void completeFactsCreateARecordInTheReviewQueueAndPublishTheChange() {
        VariantClassificationService service = service(ApprovalPolicy.ALL);

        service.onCatalogFactsChanged(event("nuts-seeds", "AT00000001", EXEMPT_NUTS, Map.of(), false));

        assertThat(records.records.get("AT00000001").status()).isEqualTo(RecordStatus.PENDING_REVIEW);
        assertThat(records.audit("AT00000001")).extracting(AuditEntry::action).containsExactly(AuditAction.SUBMITTED);
        assertThat(records.decisions("AT00000001")).singleElement().satisfies(d ->
                assertThat(d.internalTaxCode()).isEqualTo("AT.NUTS_SEEDS.PLAIN_MULTI_SERVING_2_5OZ_PLUS"));
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publish(eq("tax.classification.changed"), eq("taxreturns"), eq("AT00000001"), payload.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) payload.getValue();
        assertThat(p).containsEntry("kind", "VARIANT_CLASSIFICATION").containsEntry("status", "PENDING_REVIEW")
                .containsEntry("outcome", "EXEMPT").containsEntry("ruleVersion", TestRules.RULES_V1);
    }

    @Test
    void incompleteFactsGiveAMissingFactsRecordNotAGuess() {
        service(ApprovalPolicy.ALL).onCatalogFactsChanged(event("nuts-seeds", "AT00000002",
                Map.of("is_candy", false), Map.of(), false));

        VariantClassificationRepository records = this.records;
        assertThat(records.find("AT00000002").orElseThrow().status()).isEqualTo(RecordStatus.MISSING_FACTS);
        assertThat(this.records.decisions("AT00000002")).singleElement().satisfies(d -> {
            assertThat(d.outcome()).isNull();
            assertThat(d.missingFacts()).isNotEmpty();
        });
    }

    @Test
    void replayingTheSameEventChangesNothing() {
        VariantClassificationService service = service(ApprovalPolicy.ALL);
        CatalogFactsChanged e = event("nuts-seeds", "AT00000003", EXEMPT_NUTS, Map.of(), false);

        service.onCatalogFactsChanged(e);
        service.onCatalogFactsChanged(e);

        assertThat(records.audit("AT00000003")).hasSize(1);
        verify(publisher, times(1)).publish(any(), any(), any(), any());
    }

    @Test
    void approvalMakesTheRecordChargeableAndIsAudited() {
        VariantClassificationService service = service(ApprovalPolicy.ALL);
        service.onCatalogFactsChanged(event("nuts-seeds", "AT00000004", EXEMPT_NUTS, Map.of(), false));

        ClassificationView v = service.approve("AT00000004", new ApproveCommand("Label checked", null, null, null));

        assertThat(v.record().status()).isEqualTo(RecordStatus.APPROVED);
        assertThat(v.record().reviewedBy()).isEqualTo("STAFF:staff_1");
        assertThat(v.audit()).extracting(AuditEntry::action).containsExactly(AuditAction.SUBMITTED, AuditAction.APPROVED);
        assertThat(v.audit().getLast().reason()).isEqualTo("Label checked");
        verify(publisher, times(2)).publish(any(), any(), eq("AT00000004"), any());
    }

    @Test
    void aFactEditThatChangesTheOutcomeSendsAnApprovedRecordBackToReview() {
        VariantClassificationService service = service(ApprovalPolicy.ALL);
        service.onCatalogFactsChanged(event("nuts-seeds", "AT00000005", EXEMPT_NUTS, Map.of(), false));
        service.approve("AT00000005", new ApproveCommand(null, null, null, null));

        service.onCatalogFactsChanged(event("nuts-seeds", "AT00000005", TAXABLE_NUTS, Map.of(), false));

        assertThat(records.records.get("AT00000005").status()).isEqualTo(RecordStatus.PENDING_REVIEW);
        AuditEntry stale = records.audit("AT00000005").getLast();
        assertThat(stale.action()).isEqualTo(AuditAction.MARKED_STALE);
        assertThat(stale.reason()).contains("EXEMPT").contains("TAXABLE");
    }

    @Test
    void aFactEditThatKeepsTheOutcomeKeepsTheApproval() {
        VariantClassificationService service = service(ApprovalPolicy.ALL);
        service.onCatalogFactsChanged(event("nuts-seeds", "AT00000006", EXEMPT_NUTS, Map.of(), false));
        service.approve("AT00000006", new ApproveCommand(null, null, null, null));
        Map<String, Object> biggerPack = Map.of("is_candy", false, "candy_coated", false, "net_weight_oz", 16,
                "servings_per_package", 8, "individual_serving", false);

        service.onCatalogFactsChanged(event("nuts-seeds", "AT00000006", biggerPack, Map.of(), false));

        assertThat(records.records.get("AT00000006").status()).isEqualTo(RecordStatus.APPROVED);
        assertThat(records.audit("AT00000006").getLast().action()).isEqualTo(AuditAction.FACTS_CHANGED);
    }

    @Test
    void newEvidenceOrANewCategoryAlwaysNeedsAFreshReview() {
        VariantClassificationService service = service(ApprovalPolicy.ALL);
        service.onCatalogFactsChanged(event("nuts-seeds", "AT00000007", EXEMPT_NUTS, Map.of(), false));
        service.approve("AT00000007", new ApproveCommand(null, null, null, null));

        service.onCatalogFactsChanged(event("nuts-seeds", "AT00000007", EXEMPT_NUTS, Map.of(), true));
        assertThat(records.records.get("AT00000007").status()).isEqualTo(RecordStatus.PENDING_REVIEW);

        service.approve("AT00000007", new ApproveCommand(null, null, null, null));
        service.onCatalogFactsChanged(event("rice-grains", "AT00000007", Map.of(), Map.of(), false));
        assertThat(records.records.get("AT00000007").status()).isEqualTo(RecordStatus.PENDING_REVIEW);
        assertThat(records.audit("AT00000007").getLast().reason()).contains("category changed");
    }

    @Test
    void aGatedCategoryCannotBeApprovedUntilTheProfileIsLifted() {
        VariantClassificationService service = service(ApprovalPolicy.ALL);
        service.onCatalogFactsChanged(event("toys-games", "AT00000008", Map.of(), Map.of(), false));

        assertThatThrownBy(() -> service.approve("AT00000008", new ApproveCommand(null, null, null, null)))
                .isInstanceOf(ReturnRuleViolation.class).satisfies(ex ->
                        assertThat(((ReturnRuleViolation) ex).code()).isEqualTo("tax.classification-not-resolvable"));

        // REFINE_CODE: the reviewer records the verified provider code on the variant, then approves
        ClassificationView v = service.approve("AT00000008", new ApproveCommand("Verified in Stripe's list", null,
                Map.of("STRIPE", "txcd_20010001"), null));
        assertThat(v.record().status()).isEqualTo(RecordStatus.APPROVED);
        assertThat(v.record().refinedProviderCodes()).containsEntry("STRIPE", "txcd_20010001");
    }

    @Test
    void staffFactsAreSetAtApprovalAndOnlyStaffOwnedOnesAreAccepted() {
        rules.status("audiobooks", ProfileStatus.ACTIVE);
        VariantClassificationService service = service(ApprovalPolicy.ALL);
        service.onCatalogFactsChanged(event("audiobooks", "AT00000009", Map.of("delivery_method", "DOWNLOAD"), Map.of(),
                false));
        assertThat(records.records.get("AT00000009").status()).isEqualTo(RecordStatus.MISSING_FACTS);

        assertThatThrownBy(() -> service.approve("AT00000009", new ApproveCommand(null, Map.of("delivery_method", "X"),
                null, null))).isInstanceOf(ReturnRuleViolation.class).hasMessageContaining("not a fact that tax review sets");

        ClassificationView v = service.approve("AT00000009", new ApproveCommand("Not exempt", Map.of(
                "exemption_confirmed", false), null, null));
        assertThat(v.record().staffFacts()).containsEntry("exemption_confirmed", false);
        assertThat(v.resolution().outcome().name()).isEqualTo("TAXABLE");
    }

    @Test
    void reclassifyMovesTheVariantKeepsTheAuditAndReturnsItToReview() {
        VariantClassificationService service = service(ApprovalPolicy.ALL);
        service.onCatalogFactsChanged(event("nuts-seeds", "AT00000010", EXEMPT_NUTS, Map.of(), false));
        service.approve("AT00000010", new ApproveCommand(null, null, null, null));

        ClassificationView v = service.reclassify("AT00000010", new ReclassifyCommand("rice-grains", null,
                "These are rice crackers"));

        assertThat(v.record().categoryCode()).isEqualTo("rice-grains");
        assertThat(v.record().status()).isEqualTo(RecordStatus.PENDING_REVIEW);
        assertThat(v.audit()).extracting(AuditEntry::action).containsExactly(AuditAction.SUBMITTED, AuditAction.APPROVED,
                AuditAction.RECLASSIFIED);
        assertThat(v.audit().getLast().before()).containsEntry("categoryCode", "nuts-seeds");
        assertThat(v.audit().getLast().after()).containsEntry("categoryCode", "rice-grains");
        assertThatThrownBy(() -> service.reclassify("AT00000010", new ReclassifyCommand("rice-grains", null, "x")))
                .isInstanceOf(ReturnRuleViolation.class);
        assertThatThrownBy(() -> service.reclassify("AT00000010", new ReclassifyCommand("nope", null, "Wrong code")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void theSensitivePolicyApprovesPlainTaxableRecordsButNeverExemptOnes() {
        VariantClassificationService service = service(ApprovalPolicy.SENSITIVE);

        service.onCatalogFactsChanged(event("nuts-seeds", "AT00000011", TAXABLE_NUTS, Map.of(), false));
        service.onCatalogFactsChanged(event("nuts-seeds", "AT00000012", EXEMPT_NUTS, Map.of(), false));

        assertThat(records.records.get("AT00000011").status()).isEqualTo(RecordStatus.APPROVED);
        assertThat(records.records.get("AT00000011").reviewedBy()).isEqualTo("SYSTEM:auto-approval");
        assertThat(records.records.get("AT00000012").status()).isEqualTo(RecordStatus.PENDING_REVIEW);
    }

    @Test
    void anUnknownCategoryIsRecordedAsMissingNotLostOrRetriedForever() {
        service(ApprovalPolicy.ALL).onCatalogFactsChanged(event("not-a-category", "AT00000013", Map.of(), Map.of(), false));

        assertThat(records.records.get("AT00000013").status()).isEqualTo(RecordStatus.MISSING_FACTS);
        verify(publisher, never()).publish(any(), any(), eq("other"), any());
    }

    @Test
    void anotherWriterWinningTheRaceIsAConflictNotALostUpdate() {
        VariantClassificationService service = service(ApprovalPolicy.ALL);
        service.onCatalogFactsChanged(event("nuts-seeds", "AT00000014", EXEMPT_NUTS, Map.of(), false));
        records.records.put("AT00000014", records.records.get("AT00000014"));
        var stale = records.records.get("AT00000014");

        records.update(stale);   // someone else moves the version on
        assertThatThrownBy(() -> records.update(stale))
                .isInstanceOf(VariantClassificationRepository.StaleRecordException.class);
    }

    @Test
    void requestingInformationIsStoredAuditedPublishedAndClearedWhenTheFactsChange() {
        VariantClassificationService service = service(ApprovalPolicy.ALL);
        service.onCatalogFactsChanged(event("nuts-seeds", "AT00000015", EXEMPT_NUTS, Map.of(), false));

        assertThatThrownBy(() -> service.requestInformation("AT00000015", "too short"))
                .isInstanceOf(ReturnRuleViolation.class);
        ClassificationView v = service.requestInformation("AT00000015", "Please upload a photo of the label.");

        assertThat(v.record().informationRequest().message()).isEqualTo("Please upload a photo of the label.");
        assertThat(v.record().informationRequest().requestedBy()).isEqualTo("STAFF:staff_1");
        assertThat(v.audit()).extracting(AuditEntry::action).contains(AuditAction.INFORMATION_REQUESTED);
        assertThat(v.record().status()).isEqualTo(RecordStatus.PENDING_REVIEW);
        verify(publisher, times(2)).publish(eq("tax.classification.changed"), eq("taxreturns"), eq("AT00000015"), any());

        service.onCatalogFactsChanged(event("nuts-seeds", "AT00000015", TAXABLE_NUTS, Map.of(), false));
        assertThat(records.records.get("AT00000015").informationRequest()).as("seller answered").isNull();
    }

    @Test
    void theProductTitleIsKeptSoTheReviewQueueShowsNamesAndALaterEventFillsItWithoutNoise() {
        VariantClassificationService service = service(ApprovalPolicy.ALL);
        service.onCatalogFactsChanged(event("nuts-seeds", "AT00000090", EXEMPT_NUTS, Map.of(), false));
        assertThat(records.records.get("AT00000090").title()).isNull();

        service.onCatalogFactsChanged(new CatalogFactsChanged(PRODUCT, "org_a", "nuts-seeds", "nuts-seeds", Map.of(),
                List.of(new Variant("AT00000090", null, EXEMPT_NUTS, false)), "seller_1", TestRules.CLOCK.instant(),
                "Roasted cashews"));

        assertThat(records.records.get("AT00000090").title()).isEqualTo("Roasted cashews");
        assertThat(records.records.get("AT00000090").status()).isEqualTo(RecordStatus.PENDING_REVIEW);
        assertThat(records.audit("AT00000090")).hasSize(1);
        verify(publisher, times(1)).publish(any(), any(), eq("AT00000090"), any());
    }

    @Test
    void searchFiltersByReasonAndTextAndNamesTheSellersOfEachRecord() {
        VariantClassificationService service = service(ApprovalPolicy.ALL);
        service.onCatalogFactsChanged(event("nuts-seeds", "AT00000001", EXEMPT_NUTS, Map.of(), false));
        service.onCatalogFactsChanged(event("nuts-seeds", "AT00000002", Map.of("is_candy", false), Map.of(), false));

        VariantClassificationService.ReviewList missing = service.search(new VariantClassificationRepository.Filter(null, null, null,
                null, false, ReviewReason.MISSING_FACT, null, null, false), 0, 25);
        VariantClassificationService.ReviewList bySeller = service.search(new VariantClassificationRepository.Filter(null, null, null,
                "org name matched", false, null, List.of("org_a"), null, false), 0, 25);

        assertThat(missing.items()).extracting(VariantClassification::atin).containsExactly("AT00000002");
        assertThat(bySeller.items()).extracting(VariantClassification::atin).containsExactlyInAnyOrder("AT00000001", "AT00000002");
        assertThat(bySeller.sellers()).containsEntry("AT00000001", List.of("org_a")).containsEntry("AT00000002", List.of("org_a"));
    }
}
