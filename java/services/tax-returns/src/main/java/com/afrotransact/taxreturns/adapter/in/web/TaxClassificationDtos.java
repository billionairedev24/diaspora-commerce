package com.afrotransact.taxreturns.adapter.in.web;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.taxreturns.application.ClassificationResolveService;
import com.afrotransact.taxreturns.application.SellerListingDecisionService;
import com.afrotransact.taxreturns.application.TaxProfileService;
import com.afrotransact.taxreturns.application.TaxShadowService;
import com.afrotransact.taxreturns.application.VariantClassificationService;
import com.afrotransact.taxreturns.domain.profile.AuditEntry;
import com.afrotransact.taxreturns.domain.profile.DecisionRow;
import com.afrotransact.taxreturns.domain.profile.Evidence;
import com.afrotransact.taxreturns.domain.profile.FactDefinition;
import com.afrotransact.taxreturns.domain.profile.ListingDecision;
import com.afrotransact.taxreturns.domain.profile.ProfileFact;
import com.afrotransact.taxreturns.domain.profile.ProfileStatus;
import com.afrotransact.taxreturns.domain.profile.ProviderCode;
import com.afrotransact.taxreturns.domain.profile.Resolution;
import com.afrotransact.taxreturns.domain.profile.StatusReason;
import com.afrotransact.taxreturns.domain.profile.TaxProfile;
import com.afrotransact.taxreturns.domain.profile.VariantClassification;
import com.afrotransact.taxreturns.domain.shadow.ShadowRow;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import com.afrotransact.taxreturns.port.ShadowQuoteRepository;
import com.afrotransact.taxreturns.port.TaxProfileRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Bodies of the classification, profile, provider code map, variant record and shadow report endpoints. */
final class TaxClassificationDtos {

    private TaxClassificationDtos() {
    }

    @Schema(name = "ClassificationResolveRequest")
    record ResolveRequest(@NotBlank String categoryCode, String productTypeCode,
                          @NotNull Map<String, Object> facts, Map<String, Object> sellerBehaviour,
                          Map<String, Object> context, @Size(max = 2) String shipToState, LocalDate saleDate) {

        ClassificationResolveService.ResolveCommand toCommand() {
            return new ClassificationResolveService.ResolveCommand(categoryCode, productTypeCode, facts, sellerBehaviour,
                    context, shipToState, saleDate);
        }
    }

    @Schema(name = "ListingDecisionStatusRequest")
    record ListingStatusRequest(@NotNull @Size(min = 1, max = 500) List<@Valid ListingStatusItem> items) {
    }

    @Schema(name = "ListingDecisionRequest", description = "Ask for a seller's decision on a variant from the page's facts")
    record SellerDecisionRequest(@NotBlank @Size(max = 10) String atin, @NotBlank @Size(max = 80) String sellerOrgId,
                                 @Schema(description = "The seller's behaviour facts for this listing (sold_heated, "
                                         + "utensils_provided ...). Empty means none given.")
                                 Map<String, Object> sellerBehaviour) {
    }

    @Schema(name = "ListingDecisionState")
    record SellerDecisionState(String atin, String sellerOrgId,
                               @Schema(description = "RESOLVED, MISSING (no stored decision) or the unresolved "
                                       + "ResolveStatus (MISSING_FACTS, BLOCKED, NEEDS_APPROVAL, REFINE_CODE, "
                                       + "CONFLICT_OPEN)") String status,
                               @Schema(description = "True only for RESOLVED with a stored decision") boolean resolved,
                               String outcome, String internalTaxCode, String ruleVersion, Instant decidedAt,
                               @Schema(description = "Plain reasons when not resolved") List<String> reasons) {

        static SellerDecisionState of(SellerListingDecisionService.State s) {
            return new SellerDecisionState(s.atin(), s.sellerOrgId(), s.status(), s.resolved(), s.outcome(),
                    s.internalTaxCode(), s.ruleVersion(), s.decidedAt(), s.reasons());
        }
    }

    @Schema(name = "ListingDecisionStatusItem")
    record ListingStatusItem(@NotBlank String atin, String sellerOrgId) {
    }

    @Schema(name = "ListingDecisionStatusResponse", description = "One answer per requested ATIN")
    record ListingStatusResponse(List<ListingStatusAnswer> items) {
    }

    @Schema(name = "ListingDecisionStatusAnswer")
    record ListingStatusAnswer(String atin, @Schema(description = "RESOLVED, MISSING_FACTS, BLOCKED, NEEDS_APPROVAL, "
            + "REFINE_CODE or CONFLICT_OPEN. Only RESOLVED may be listed or sold") String decisionStatus,
                               @Schema(description = "The classification record's status (APPROVED, PENDING_REVIEW...); null when none")
                               String recordStatus, @Schema(description = "Why it is not resolved, plain words") String reason) {
    }

    @Schema(name = "MissingFact")
    record MissingFactDto(String factId, String label, String message) {
    }

    @Schema(name = "ClassificationResolveResponse")
    record ResolveResponse(String status, String outcome, String internalTaxCode, Map<String, String> providerCodes,
                           String rowId, String rateDisplay, List<MissingFactDto> missingFacts, String ruleVersion,
                           String reason, Integer openDecision) {

        static ResolveResponse of(Resolution r) {
            return new ResolveResponse(r.status().name(), r.outcome() == null ? null : r.outcome().name(),
                    r.internalTaxCode(), r.providerCodes(), r.rowId(), r.rateDisplay(),
                    r.missingFacts().stream().map(m -> new MissingFactDto(m.factId(), m.label(), m.message())).toList(),
                    r.ruleVersion(), r.reason(), r.openDecision() == 0 ? null : r.openDecision());
        }

        static ResolveResponse of(ListingDecision d) {
            return new ResolveResponse(d.status().name(), d.outcome() == null ? null : d.outcome().name(),
                    d.internalTaxCode(), d.providerCodes(), null, null,
                    d.missingFacts().stream().map(m -> new MissingFactDto(m.factId(), m.label(), m.message())).toList(),
                    d.ruleVersion(), d.reason(), null);
        }
    }

    @Schema(name = "ChoiceDto")
    record ChoiceDto(String value, String label) {
    }

    /** A fact as a category uses it: the definition and the category's requirement in one object. */
    @Schema(name = "TaxProfileFact")
    record ProfileFactDto(String id, String fact, String label, String kind, String unit, String owner, String help,
                          boolean evidence, List<ChoiceDto> choices, boolean required, Map<String, Object> requiredWhen) {

        static ProfileFactDto of(ProfileFact f) {
            FactDefinition d = f.definition();
            List<String> allowed = f.allowedChoices();
            List<ChoiceDto> choices = d.choices().stream().filter(c -> allowed.contains(c.value()))
                    .map(c -> new ChoiceDto(c.value(), c.label())).toList();
            return new ProfileFactDto(d.id(), d.id(), d.label(), d.kind().name(), d.unit(), d.owner().name(), d.help(),
                    d.evidence(), choices, f.required(), f.requiredWhen() == null ? null : f.requiredWhen().toTree());
        }
    }

    @Schema(name = "DecisionRow")
    record DecisionRowDto(String id, int scenario, Map<String, Object> when, String condition, String outcome,
                          String internalTaxCode, String stripeCode, Map<String, String> providerCodes,
                          String rateDisplay, String note) {

        static DecisionRowDto of(DecisionRow r) {
            return new DecisionRowDto(r.id(), r.scenario(), r.when() == null ? null : r.when().toTree(), r.condition(),
                    r.outcome().name(), r.internalTaxCode(), r.providerCodes().get("STRIPE"), r.providerCodes(),
                    r.rateDisplay(), r.note());
        }
    }

    @Schema(name = "TaxProfileStatusReason")
    record StatusReasonDto(String status, Integer decision, String text) {

        static StatusReasonDto of(StatusReason r) {
            return new StatusReasonDto(r.status().name(), r.decision() == 0 ? null : r.decision(), r.text());
        }
    }

    @Schema(name = "TaxProfileSummary")
    record ProfileSummary(String categoryCode, String name, String department, String departmentName, String status,
                          String defaultOutcome, int scenarioCount, Integer openDecision, String ruleVersion,
                          List<String> stripeCodes) {

        static ProfileSummary of(TaxProfileService.ProfileView v) {
            TaxProfile p = v.profile();
            return new ProfileSummary(p.categoryCode(), p.name(), p.departmentCode(), p.departmentName(),
                    p.status().name(), p.defaultOutcome(), p.rows().size(),
                    p.status() == ProfileStatus.ACTIVE || p.openDecision() == 0 ? null : p.openDecision(),
                    v.ruleVersion(), p.stripeCodes());
        }
    }

    @Schema(name = "TaxProfileHistoryEntry")
    record HistoryDto(Instant at, String actor, String kind, String reason, String approvedBy, String decisionRef,
                      String ruleVersion, Map<String, Object> before, Map<String, Object> after) {

        static HistoryDto of(TaxProfileRepository.AuditRow r) {
            return new HistoryDto(r.at(), r.actor(), r.kind(), r.reason(), r.approvedBy(), r.decisionRef(), r.ruleVersion(),
                    r.before(), r.after());
        }
    }

    @Schema(name = "TaxProfile")
    record ProfileDetail(String categoryCode, String name, String department, String departmentName, String status,
                         String defaultOutcome, int scenarioCount, Integer openDecision, String ruleVersion,
                         List<String> stripeCodes, List<String> conflictingCodes, List<StatusReasonDto> statusReasons,
                         List<String> factGroups, List<ProfileFactDto> facts, List<DecisionRowDto> decisionTable,
                         String changedBy, Instant changedAt, String note, List<HistoryDto> history) {

        static ProfileDetail of(TaxProfileService.ProfileView v) {
            TaxProfile p = v.profile();
            ProfileSummary s = ProfileSummary.of(v);
            return new ProfileDetail(s.categoryCode(), s.name(), s.department(), s.departmentName(), s.status(),
                    s.defaultOutcome(), s.scenarioCount(), s.openDecision(), s.ruleVersion(), p.stripeCodes(),
                    p.conflictingCodes(), p.statusReasons().stream().map(StatusReasonDto::of).toList(), p.factGroups(),
                    p.facts().stream().map(ProfileFactDto::of).toList(),
                    p.rows().stream().map(DecisionRowDto::of).toList(), v.stamp() == null ? null : v.stamp().by(),
                    v.stamp() == null ? null : v.stamp().at(), p.note(),
                    v.history().stream().map(HistoryDto::of).toList());
        }
    }

    @Schema(name = "TaxProfileStatusChange")
    record StatusChangeRequest(@NotNull ProfileStatus status, @NotBlank @Size(min = 3) String reason, String approvedBy,
                               String decisionRef) {
    }

    @Schema(name = "ProviderCodeMapEntry")
    record ProviderCodeEntry(String internalTaxCode, String categoryCode, String outcome, String provider,
                             String providerCode, boolean verified, int mapVersion, String changedBy, Instant changedAt,
                             boolean active, String verifiedNote, String note) {

        static ProviderCodeEntry of(ProviderCode c) {
            return new ProviderCodeEntry(c.internalTaxCode(), c.categoryCode(), c.outcome().name(), c.provider().name(),
                    c.providerCode(), c.verified(), c.mapVersion(), c.changedBy(), c.changedAt(), c.active(),
                    c.verifiedNote(), c.verifiedNote());
        }
    }

    @Schema(name = "ProviderCodeMapUpdate")
    record ProviderCodeUpdate(@NotNull TaxProviderId provider,
                              @Schema(description = "Null clears the mapping") String providerCode, Boolean verified,
                              @NotBlank @Size(min = 3) String reason) {
    }

    @Schema(name = "ClassificationEvidence")
    record EvidenceDto(String factId, String fileName, String url, String uploadedBy, Instant uploadedAt) {

        static EvidenceDto of(Evidence e) {
            return new EvidenceDto(e.factId(), e.fileName(), e.url(), e.uploadedBy(), e.uploadedAt());
        }
    }

    @Schema(name = "ClassificationAuditEntry")
    record AuditDto(Instant at, String actor, String action, String reason, String ruleVersion,
                    Map<String, Object> before, Map<String, Object> after) {

        static AuditDto of(AuditEntry a) {
            return new AuditDto(a.at(), a.actor(), a.action().name(), a.reason(), a.ruleVersion(), a.before(), a.after());
        }
    }

    @Schema(name = "ClassificationSellerDecision")
    record SellerDecisionDto(String sellerOrgId, Map<String, Object> sellerBehaviour, ResolveResponse resolution) {
    }

    @Schema(name = "ClassificationInformationRequest")
    record InfoRequestDto(String message, String requestedBy, Instant requestedAt) {

        static InfoRequestDto of(VariantClassification v) {
            return v.informationRequest() == null ? null : new InfoRequestDto(v.informationRequest().message(),
                    v.informationRequest().requestedBy(), v.informationRequest().requestedAt());
        }
    }

    @Schema(name = "ClassificationInformationRequestBody")
    record InformationRequestBody(@NotBlank @Size(min = 10, max = 1000) String message, String reason) {
    }

    @Schema(name = "VariantClassification")
    record VariantDto(String atin, String gtin, UUID productId, String title, String categoryCode, String productTypeCode,
                      String status, Map<String, Object> facts, Map<String, Object> staffFacts,
                      List<EvidenceDto> evidence, Map<String, String> refinedProviderCodes, ResolveResponse resolution,
                      List<SellerDecisionDto> decisions, String ownerOrgId, String classifiedBy, Instant classifiedAt,
                      String reviewedBy, Instant reviewedAt, String ruleVersion, List<AuditDto> audit,
                      InfoRequestDto informationRequest, Long ordersAffected,
                      @Schema(description = "Approved by the system and waiting in the audit sample for a reviewer to "
                              + "confirm or reclassify it; the item is live meanwhile") boolean auditSample,
                      Instant auditSampledAt,
                      @Schema(description = "Why a person has to review it, in plain words: a conflict, no matching "
                              + "rule, a missing answer or proof, a regulated category or a category that awaits "
                              + "sign-off. Null when nothing waits, or when the rules would approve it (a staff "
                              + "move, an open question to the seller).") String reviewReason) {

        /** A list item: the record without its audit, evidence and stored decisions. */
        static VariantDto light(VariantClassification v, String reviewReason) {
            return new VariantDto(v.atin(), v.gtin(), v.productId(), v.title(), v.categoryCode(), v.productTypeCode(),
                    v.status().name(), v.facts(), v.staffFacts(), null, v.refinedProviderCodes(),
                    v.resolution() == null ? null : ResolveResponse.of(v.resolution()), null, v.ownerOrgId(),
                    v.classifiedBy(), v.classifiedAt(), v.reviewedBy(), v.reviewedAt(), v.ruleVersion(), null,
                    InfoRequestDto.of(v), null, v.auditItem(), v.auditSampledAt(), reviewReason);
        }

        static VariantDto of(VariantClassificationService.ClassificationView view) {
            VariantClassification v = view.record();
            List<SellerDecisionDto> decisions = new ArrayList<>();
            for (ListingDecision d : view.decisions()) {
                ResolveResponse r = ResolveResponse.of(d);
                if (d.sellerOrgIds().isEmpty()) {
                    decisions.add(new SellerDecisionDto(null, d.sellerBehaviour(), r));
                }
                d.sellerOrgIds().forEach(org -> decisions.add(new SellerDecisionDto(org, d.sellerBehaviour(), r)));
            }
            return new VariantDto(v.atin(), v.gtin(), v.productId(), v.title(), v.categoryCode(), v.productTypeCode(),
                    v.status().name(), v.facts(), v.staffFacts(), view.evidence().stream().map(EvidenceDto::of).toList(),
                    v.refinedProviderCodes(), view.resolution() == null ? null : ResolveResponse.of(view.resolution()),
                    decisions, v.ownerOrgId(), v.classifiedBy(), v.classifiedAt(), v.reviewedBy(), v.reviewedAt(),
                    v.ruleVersion(), view.audit().stream().map(AuditDto::of).toList(), InfoRequestDto.of(v),
                    view.ordersAffected(), v.auditItem(), v.auditSampledAt(), view.reviewReason());
        }
    }

    @Schema(name = "VariantClassificationPage")
    record VariantPage(List<VariantDto> items, int page, int size, long total) {
    }

    @Schema(name = "ClassificationApproveRequest")
    record ApproveRequest(String reason,
                          @Schema(description = "Facts owned by STAFF, such as exemption_confirmed")
                          Map<String, Object> staffFacts, Map<String, String> refinedProviderCodes,
                          @Schema(description = "Reference of the tax professional's sign-off") String decisionRef) {

        VariantClassificationService.ApproveCommand toCommand() {
            return new VariantClassificationService.ApproveCommand(reason, staffFacts, refinedProviderCodes, decisionRef);
        }
    }

    @Schema(name = "ClassificationReclassifyRequest")
    record ReclassifyRequest(@NotBlank String categoryCode, String productTypeCode, @NotBlank @Size(min = 3) String reason) {

        VariantClassificationService.ReclassifyCommand toCommand() {
            return new VariantClassificationService.ReclassifyCommand(categoryCode, productTypeCode, reason);
        }
    }

    @Schema(name = "ShadowQuoteSide")
    record ShadowSide(String outcome, BigDecimal rate, Long taxMinor, String error) {
    }

    @Schema(name = "ShadowDifference")
    record ShadowDifference(UUID orderId, String orderRef, String lineId, String atin, String categoryCode, String state,
                            String shipToState,
                            String internalTaxCode, String providerCode, String kind, ShadowSide inHouse,
                            ShadowSide provider, long differenceMinor, Instant quotedAt) {

        static ShadowDifference of(ShadowRow r) {
            return new ShadowDifference(uuid(r.orderRef()), r.orderRef(), r.lineId(), r.atin(), r.categoryCode(),
                    r.state(), r.state(), r.internalTaxCode(), r.providerCode(), r.kind().name(),
                    new ShadowSide(r.inHouseOutcome() == null ? null : r.inHouseOutcome().name(), r.inHouseRate(),
                            r.inHouseTaxMinor(), null),
                    new ShadowSide(r.providerOutcome() == null ? null : r.providerOutcome().name(), r.providerRate(),
                            r.providerTaxMinor(), r.providerError()), r.differenceMinor(), r.quotedAt());
        }

        private static UUID uuid(String s) {
            try {
                return s == null ? null : UUID.fromString(s);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
    }

    @Schema(name = "ShadowGroup", description = "A group of compared lines and how many of them disagree")
    record GroupDto(String key, String internalTaxCode, String providerCode, long lines, long differing,
                    @Schema(description = "differing / lines") BigDecimal disagreementRate, long absoluteDifferenceMinor) {

        static GroupDto of(ShadowQuoteRepository.Group g, boolean code) {
            BigDecimal rate = g.lines() == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(g.differing()).divide(BigDecimal.valueOf(g.lines()), 4,
                    RoundingMode.HALF_UP);
            return new GroupDto(g.key(), code ? g.key() : null, g.providerCode(), g.lines(), g.differing(), rate,
                    g.absoluteDifferenceMinor());
        }
    }

    @Schema(name = "ShadowReport")
    record ShadowReportDto(LocalDate from, LocalDate to, TaxProviderId provider, long linesCompared, long linesDiffering,
                           long absoluteDifferenceMinor, long netDifferenceMinor,
                           List<GroupDto> byInternalTaxCode, List<GroupDto> byCategory, List<GroupDto> byOutcome,
                           List<GroupDto> byState, List<GroupDto> byKind, List<ShadowDifference> differences, int page,
                           int size, Map<String, Double> thresholds) {

        static ShadowReportDto of(TaxShadowService.Report r) {
            return new ShadowReportDto(r.from(), r.to(), r.provider(), r.totals().compared(), r.totals().differing(),
                    r.totals().absoluteDifferenceMinor(), r.totals().netDifferenceMinor(),
                    r.byCode().stream().map(g -> GroupDto.of(g, true)).toList(),
                    r.byCategory().stream().map(g -> GroupDto.of(g, false)).toList(),
                    r.byOutcome().stream().map(g -> GroupDto.of(g, false)).toList(),
                    r.byState().stream().map(g -> GroupDto.of(g, false)).toList(),
                    r.byKind().stream().map(g -> GroupDto.of(g, false)).toList(),
                    r.differences().stream().map(ShadowDifference::of).toList(), r.page(), r.size(), r.thresholds());
        }
    }
}
