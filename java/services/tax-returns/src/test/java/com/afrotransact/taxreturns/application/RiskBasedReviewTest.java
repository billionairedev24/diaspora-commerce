package com.afrotransact.taxreturns.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.taxreturns.application.VariantClassificationService.ApproveCommand;
import com.afrotransact.taxreturns.application.VariantClassificationService.CatalogFactsChanged;
import com.afrotransact.taxreturns.application.VariantClassificationService.ClassificationView;
import com.afrotransact.taxreturns.application.VariantClassificationService.ReclassifyCommand;
import com.afrotransact.taxreturns.application.VariantClassificationService.Variant;
import com.afrotransact.taxreturns.domain.profile.AuditAction;
import com.afrotransact.taxreturns.domain.profile.AuditEntry;
import com.afrotransact.taxreturns.domain.profile.ProfileStatus;
import com.afrotransact.taxreturns.domain.profile.RecordStatus;
import com.afrotransact.taxreturns.domain.profile.ReviewMode;
import com.afrotransact.taxreturns.domain.profile.ReviewSettings;
import com.afrotransact.taxreturns.port.TaxTelemetry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** taxReview.mode: ALL keeps today's behaviour; RISK_BASED approves by rule, samples for audit and queues the rest. */
class RiskBasedReviewTest {

    private static final UUID PRODUCT = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Map<String, Object> EXEMPT_NUTS = Map.of("is_candy", false, "candy_coated", false,
            "net_weight_oz", 8, "servings_per_package", 4, "individual_serving", false);
    private static final Map<String, Object> TAXABLE_NUTS = Map.of("is_candy", false, "candy_coated", false,
            "net_weight_oz", 1, "servings_per_package", 1, "individual_serving", true);

    private final TestRules rules = new TestRules();
    private final InMemoryClassifications records = new InMemoryClassifications();
    private final InMemoryReviewSettings settings = new InMemoryReviewSettings();
    private final DomainEventPublisher publisher = mock(DomainEventPublisher.class);
    private final VariantClassificationService service = new VariantClassificationServiceImpl(records, rules.engine,
            new ListingDecisions(records), new ClassificationEvents(publisher, mock(TaxTelemetry.class)),
            TestRules.props(null), settings, TestActors.staff("tax:classify-admin", "tax:read"), TestRules.CLOCK);

    private void riskBased(int auditPercent, String... alwaysReview) {
        settings.change(new ReviewSettings(ReviewMode.RISK_BASED, List.of(alwaysReview), auditPercent), "Agreed with the "
                + "tax professional", "staff_9", Instant.now());
    }

    private void submit(String category, String atin, Map<String, Object> facts) {
        service.onCatalogFactsChanged(new CatalogFactsChanged(PRODUCT, "org_a", category, category, Map.of(),
                List.of(new Variant(atin, null, facts, false)), "seller_1", TestRules.CLOCK.instant()));
    }

    @Test
    void theDefaultModeIsAllSoEveryRecordWaitsForAReviewer() {
        assertThat(settings.current().mode()).isEqualTo(ReviewMode.ALL);

        submit("nuts-seeds", "AT10000001", TAXABLE_NUTS);
        submit("haircare", "AT10000002", Map.of());

        assertThat(records.records.get("AT10000001").status()).isEqualTo(RecordStatus.PENDING_REVIEW);
        assertThat(records.records.get("AT10000002").status()).isEqualTo(RecordStatus.PENDING_REVIEW);
        assertThat(records.records.values()).allSatisfy(v -> assertThat(v.auditItem()).isFalse());
    }

    @Test
    void anActiveResolvedRecordIsApprovedByAutoWithTheRuleVersionTheFactsAndTheRuleInTheAudit() {
        riskBased(0);

        submit("nuts-seeds", "AT10000003", EXEMPT_NUTS);

        var rec = records.records.get("AT10000003");
        assertThat(rec.status()).isEqualTo(RecordStatus.APPROVED);
        assertThat(rec.classifiedBy()).isEqualTo("auto");
        assertThat(rec.reviewedBy()).isEqualTo("auto");
        assertThat(rec.ruleVersion()).isEqualTo(TestRules.RULES_V1);
        assertThat(rec.auditItem()).isFalse();
        List<AuditEntry> audit = records.audit("AT10000003");
        assertThat(audit).extracting(AuditEntry::action).containsExactly(AuditAction.SUBMITTED, AuditAction.APPROVED);
        AuditEntry approved = audit.getLast();
        assertThat(approved.actor()).isEqualTo("auto");
        assertThat(approved.ruleVersion()).isEqualTo(TestRules.RULES_V1);
        assertThat(approved.reason()).contains("Risk-based review").contains("ACTIVE").contains("always-review list");
        assertThat(approved.after()).containsEntry("classifiedBy", "auto").containsEntry("facts", EXEMPT_NUTS)
                .containsEntry("rule", approved.reason());
        assertThat(records.decisions("AT10000003")).singleElement().satisfies(d -> {
            assertThat(d.status().name()).isEqualTo("RESOLVED");
            assertThat(d.approvedBy()).isEqualTo("auto");
        });
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publish(eq("tax.classification.changed"), eq("taxreturns"), eq("AT10000003"), payload.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) payload.getValue();
        assertThat(p).containsEntry("status", "APPROVED").containsEntry("changedBy", "seller_1");
    }

    @Test
    void everyGateStatusAndTheAlwaysReviewListSendTheRecordToTheQueue() {
        riskBased(0, "baby-food-formula");
        int n = 0;
        for (ProfileStatus status : ProfileStatus.values()) {
            String atin = "AT2000000" + n++;
            rules.status("haircare", status);
            submit("haircare", atin, Map.of());

            assertThat(records.records.get(atin).status()).as("status %s", status)
                    .isEqualTo(status == ProfileStatus.ACTIVE ? RecordStatus.APPROVED : RecordStatus.PENDING_REVIEW);
        }
        submit("baby-food-formula", "AT20000009", Map.of());
        assertThat(records.records.get("AT20000009").status()).as("always-review list").isEqualTo(RecordStatus.PENDING_REVIEW);
        assertThat(records.records.get("AT20000009").classifiedBy()).isEqualTo("seller_1");
        assertThat(records.audit("AT20000009")).extracting(AuditEntry::action).containsExactly(AuditAction.SUBMITTED);
    }

    @Test
    void missingFactsStayMissingFactsAndGatedResolveStatusesStayInReview() {
        riskBased(0);

        submit("nuts-seeds", "AT10000004", Map.of("is_candy", false));
        submit("toys-games", "AT10000005", Map.of());

        assertThat(records.records.get("AT10000004").status()).isEqualTo(RecordStatus.MISSING_FACTS);
        assertThat(records.records.get("AT10000005").status()).isEqualTo(RecordStatus.PENDING_REVIEW);
    }

    @Test
    void aSampledItemIsAutoApprovedAndLiveAndAlsoWaitsInTheAuditQueue() {
        riskBased(100);

        submit("nuts-seeds", "AT10000006", EXEMPT_NUTS);

        var rec = records.records.get("AT10000006");
        assertThat(rec.status()).isEqualTo(RecordStatus.APPROVED);
        assertThat(rec.auditItem()).isTrue();
        assertThat(records.audit("AT10000006")).extracting(AuditEntry::action)
                .containsExactly(AuditAction.SUBMITTED, AuditAction.APPROVED, AuditAction.AUDIT_SAMPLED);
        assertThat(records.audit("AT10000006").getLast().reason()).contains("100%").contains("stays live");
        assertThat(service.list(null, null, null, null, true, 0, 10).items()).extracting(v -> v.atin())
                .containsExactly("AT10000006");
    }

    @Test
    void replayingTheEventNeitherSamplesNorApprovesTwice() {
        riskBased(100);
        CatalogFactsChanged e = new CatalogFactsChanged(PRODUCT, "org_a", "nuts-seeds", "nuts-seeds", Map.of(),
                List.of(new Variant("AT10000007", null, EXEMPT_NUTS, false)), "seller_1", TestRules.CLOCK.instant());

        service.onCatalogFactsChanged(e);
        service.onCatalogFactsChanged(e);

        assertThat(records.audit("AT10000007")).extracting(AuditEntry::action)
                .containsExactly(AuditAction.SUBMITTED, AuditAction.APPROVED, AuditAction.AUDIT_SAMPLED);
        verify(publisher, times(1)).publish(any(), any(), eq("AT10000007"), any());
    }

    @Test
    void confirmingAnAuditItemTakesItOutOfTheQueueAndKeepsItLive() {
        riskBased(100);
        submit("nuts-seeds", "AT10000008", EXEMPT_NUTS);

        ClassificationView v = service.approve("AT10000008", new ApproveCommand("Label and weight checked", null, null,
                null));

        assertThat(v.record().status()).isEqualTo(RecordStatus.APPROVED);
        assertThat(v.record().auditItem()).isFalse();
        assertThat(v.record().reviewedBy()).isEqualTo("STAFF:staff_1");
        assertThat(v.audit().getLast().action()).isEqualTo(AuditAction.AUDIT_CONFIRMED);
        assertThat(v.audit().getLast().reason()).isEqualTo("Label and weight checked");
        assertThat(service.list(null, null, null, null, true, 0, 10).items()).isEmpty();
        assertThat(records.decisions("AT10000008")).singleElement()
                .satisfies(d -> assertThat(d.status().name()).isEqualTo("RESOLVED"));
    }

    @Test
    void reclassifyingAnAuditItemReClosesTheGateAndPublishesTheChange() {
        riskBased(100);
        submit("nuts-seeds", "AT10000009", EXEMPT_NUTS);

        ClassificationView v = service.reclassify("AT10000009", new ReclassifyCommand("rice-grains", null,
                "These are rice crackers"));

        assertThat(v.record().status()).isEqualTo(RecordStatus.PENDING_REVIEW);
        assertThat(v.record().auditItem()).isFalse();
        assertThat(service.list(null, null, null, null, true, 0, 10).items()).isEmpty();
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(publisher, times(2)).publish(eq("tax.classification.changed"), eq("taxreturns"), eq("AT10000009"),
                payload.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> last = (Map<String, Object>) payload.getAllValues().getLast();
        assertThat(last).containsEntry("status", "PENDING_REVIEW").containsEntry("previousStatus", "APPROVED")
                .containsEntry("categoryCode", "rice-grains");
    }

    @Test
    void anAutoApprovedRecordWhoseOutcomeChangesIsMarkedStaleThenDecidedAgainByTheRule() {
        riskBased(0);
        submit("nuts-seeds", "AT10000010", EXEMPT_NUTS);

        submit("nuts-seeds", "AT10000010", TAXABLE_NUTS);

        assertThat(records.audit("AT10000010")).extracting(AuditEntry::action).containsExactly(AuditAction.SUBMITTED,
                AuditAction.APPROVED, AuditAction.MARKED_STALE, AuditAction.APPROVED);
        assertThat(records.audit("AT10000010").getLast().actor()).isEqualTo("auto");
        assertThat(records.records.get("AT10000010").status()).isEqualTo(RecordStatus.APPROVED);
    }

    @Test
    void switchingBackToAllSendsNewRecordsToReviewAndLeavesDecidedOnesAlone() {
        riskBased(0);
        submit("nuts-seeds", "AT10000011", EXEMPT_NUTS);
        settings.change(ReviewSettings.defaults(), "Tax professional withdrew the approval", "staff_9", Instant.now());

        submit("nuts-seeds", "AT10000012", EXEMPT_NUTS);

        assertThat(records.records.get("AT10000011").status()).isEqualTo(RecordStatus.APPROVED);
        assertThat(records.records.get("AT10000012").status()).isEqualTo(RecordStatus.PENDING_REVIEW);
    }
}
