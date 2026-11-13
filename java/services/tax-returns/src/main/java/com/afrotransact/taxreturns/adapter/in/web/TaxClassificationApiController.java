package com.afrotransact.taxreturns.adapter.in.web;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.afrotransact.taxreturns.adapter.in.web.TaxClassificationDtos.ApproveRequest;
import com.afrotransact.taxreturns.adapter.in.web.TaxClassificationDtos.InformationRequestBody;
import com.afrotransact.taxreturns.adapter.in.web.TaxClassificationDtos.ListingStatusAnswer;
import com.afrotransact.taxreturns.adapter.in.web.TaxClassificationDtos.ListingStatusRequest;
import com.afrotransact.taxreturns.adapter.in.web.TaxClassificationDtos.ListingStatusResponse;
import com.afrotransact.taxreturns.adapter.in.web.TaxClassificationDtos.ProfileDetail;
import com.afrotransact.taxreturns.adapter.in.web.TaxClassificationDtos.ProfileSummary;
import com.afrotransact.taxreturns.adapter.in.web.TaxClassificationDtos.ProviderCodeEntry;
import com.afrotransact.taxreturns.adapter.in.web.TaxClassificationDtos.ProviderCodeUpdate;
import com.afrotransact.taxreturns.adapter.in.web.TaxClassificationDtos.ReclassifyRequest;
import com.afrotransact.taxreturns.adapter.in.web.TaxClassificationDtos.SellerDecisionRequest;
import com.afrotransact.taxreturns.adapter.in.web.TaxClassificationDtos.SellerDecisionState;
import com.afrotransact.taxreturns.adapter.in.web.TaxClassificationDtos.ResolveRequest;
import com.afrotransact.taxreturns.adapter.in.web.TaxClassificationDtos.ResolveResponse;
import com.afrotransact.taxreturns.adapter.in.web.TaxClassificationDtos.ShadowReportDto;
import com.afrotransact.taxreturns.adapter.in.web.TaxClassificationDtos.StatusChangeRequest;
import com.afrotransact.taxreturns.adapter.in.web.TaxClassificationDtos.VariantDto;
import com.afrotransact.taxreturns.adapter.in.web.TaxClassificationDtos.VariantPage;
import com.afrotransact.taxreturns.application.ClassificationResolveService;
import com.afrotransact.taxreturns.application.LineDecisionService;
import com.afrotransact.taxreturns.application.ListingDecisionStatusService;
import com.afrotransact.taxreturns.application.ProviderCodeService;
import com.afrotransact.taxreturns.application.SellerListingDecisionService;
import com.afrotransact.taxreturns.application.TaxProfileService;
import com.afrotransact.taxreturns.application.TaxShadowService;
import com.afrotransact.taxreturns.application.VariantClassificationService.ReviewList;
import com.afrotransact.taxreturns.application.VariantClassificationService;
import com.afrotransact.taxreturns.domain.profile.ProfileStatus;
import com.afrotransact.taxreturns.domain.profile.RecordStatus;
import com.afrotransact.taxreturns.domain.profile.ReviewReason;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import com.afrotransact.taxreturns.port.VariantClassificationRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The category tax engine's API (contracts/openapi/tax-returns.yaml, tags Tax classification and Tax shadow): resolve,
 * profiles, the provider code map, variant classification records with their review queue, and the shadow report.
 */
@Tag(name = "Tax classification", description = "Category tax profiles, outcome-level internal codes, provider code maps, "
        + "variant classification records and review; DRAFT rules, a Texas tax professional must approve before use")
@RestController
class TaxClassificationApiController {

    private final ClassificationResolveService resolve;
    private final TaxProfileService profiles;
    private final ProviderCodeService providerCodes;
    private final VariantClassificationService variants;
    private final TaxShadowService shadow;
    private final ListingDecisionStatusService listingStatus;
    private final SellerListingDecisionService sellerDecisions;

    TaxClassificationApiController(ListingDecisionStatusService listingStatus, SellerListingDecisionService sellerDecisions, ClassificationResolveService resolve, TaxProfileService profiles,
                                   ProviderCodeService providerCodes, VariantClassificationService variants,
                                   TaxShadowService shadow) {
        this.resolve = resolve;
        this.profiles = profiles;
        this.providerCodes = providerCodes;
        this.variants = variants;
        this.shadow = shadow;
        this.listingStatus = listingStatus;
        this.sellerDecisions = sellerDecisions;
    }

    @Operation(summary = "Resolve the tax outcome of a category and its facts, without charging (tax:classify)",
            description = "Never guesses: missing facts give MISSING_FACTS with plain messages and no outcome. Act on "
                    + "status, not on outcome: only RESOLVED may be published or charged.")
    @PostMapping("/v1/tax/classifications/resolve")
    ResolveResponse resolve(@Valid @RequestBody ResolveRequest body) {
        return ResolveResponse.of(resolve.resolve(body.toCommand()));
    }

    @Operation(summary = "Is the stored tax decision of these variants RESOLVED? Batched, read-only (tax:classify)",
            description = "The catalog publish gate's authority: the same classification record and rules checkout's quote reads, "
                    + "without a ship-to, without creating a record and without storing anything. A variant with no record is "
                    + "MISSING_FACTS. Only RESOLVED may be listed or sold. At most 500 variants.")
    @PostMapping("/v1/tax/listing-decisions/status")
    ListingStatusResponse listingStatus(@Valid @RequestBody ListingStatusRequest body) {
        Map<String, LineDecisionService.ListingStatus> found = listingStatus.statusOf(body.items().stream()
                .map(i -> new ListingDecisionStatusService.Item(i.atin().trim().toUpperCase(Locale.ROOT), i.sellerOrgId()))
                .toList());
        return new ListingStatusResponse(found.entrySet().stream().map(e -> new ListingStatusAnswer(e.getKey(),
                e.getValue().status().name(), e.getValue().recordStatus(), e.getValue().reason())).toList());
    }

    @Operation(operationId = "getListingDecision",
            summary = "Does this seller have a stored tax decision on this variant? (service clients, S384)",
            description = "The decision is stored per (variant ATIN, seller behaviour) and lists the organisations it applies "
                    + "to; the page owner's decision is not another seller's. RESOLVED only when a stored, RESOLVED decision "
                    + "names this organisation under the rules in force; MISSING when none is stored. Read-only: never "
                    + "creates a decision. Needs tax:read.")
    @GetMapping("/v1/tax/listing-decisions")
    SellerDecisionState sellerDecision(@RequestParam String atin, @RequestParam String sellerOrgId) {
        return SellerDecisionState.of(sellerDecisions.find(atin, sellerOrgId));
    }

    @Operation(operationId = "requestListingDecision",
            summary = "Ask for the seller's decision on a variant from the page's facts (service clients, S384)",
            description = "Resolves the variant's classification record under the seller's own behaviour facts and, when the "
                    + "outcome is chargeable, stores the decision for this organisation. Never defaults a rate or an "
                    + "outcome: an unapproved record, a missing fact or a gated profile stores nothing and the answer says "
                    + "why. Idempotent. Needs tax:classify.")
    @PostMapping("/v1/tax/listing-decisions/request")
    SellerDecisionState requestSellerDecision(@Valid @RequestBody SellerDecisionRequest body) {
        return SellerDecisionState.of(sellerDecisions.request(body.atin(), body.sellerOrgId(), body.sellerBehaviour()));
    }

    @Operation(summary = "Category tax profiles with their status (tax:read)")
    @GetMapping("/v1/tax/profiles")
    List<ProfileSummary> listProfiles(@RequestParam(required = false) String department,
                                      @RequestParam(required = false) ProfileStatus status,
                                      @RequestParam(required = false) String q) {
        return profiles.list(department, status, q).stream().map(ProfileSummary::of).toList();
    }

    @Operation(summary = "One category tax profile with its facts and the ordered decision table (tax:read)")
    @GetMapping("/v1/tax/profiles/{categoryCode}")
    ProfileDetail getProfile(@PathVariable String categoryCode) {
        return ProfileDetail.of(profiles.get(categoryCode));
    }

    @Operation(summary = "Change a profile's status gate, with a reason (tax:classify-admin)")
    @PutMapping("/v1/tax/profiles/{categoryCode}/status")
    ProfileDetail changeStatus(@PathVariable String categoryCode, @Valid @RequestBody StatusChangeRequest body) {
        return ProfileDetail.of(profiles.changeStatus(categoryCode, new TaxProfileService.StatusChange(body.status(),
                body.reason(), body.approvedBy(), body.decisionRef())));
    }

    @Operation(summary = "The internal outcome code to provider code map (tax:read)")
    @GetMapping("/v1/tax/provider-code-map")
    List<ProviderCodeEntry> providerCodeMap(@RequestParam(required = false) TaxProviderId provider,
                                            @RequestParam(required = false) String categoryCode,
                                            @RequestParam(required = false, defaultValue = "false") boolean unmappedOnly) {
        return providerCodes.list(provider, categoryCode, unmappedOnly).stream().map(ProviderCodeEntry::of).toList();
    }

    @Operation(summary = "Set or clear one provider's code for an internal outcome code (tax:classify-admin)",
            description = "Mapping is per outcome, never per category, because a provider code can carry different "
                    + "outcomes in different scenarios. A change is versioned and audited and starts a new rule version.")
    @PutMapping("/v1/tax/provider-code-map/{internalTaxCode}")
    ProviderCodeEntry updateProviderCode(@PathVariable String internalTaxCode,
                                         @Valid @RequestBody ProviderCodeUpdate body) {
        return ProviderCodeEntry.of(providerCodes.update(internalTaxCode, new ProviderCodeService.Update(body.provider(),
                body.providerCode(), body.verified(), body.reason())));
    }

    @Operation(summary = "Variant classification records; the review queue is status=PENDING_REVIEW (tax:read)",
            description = "auditSample=true lists the audit sample instead: items the system approved under the "
                    + "risk-based review rule (taxReview.mode RISK_BASED) that are live and wait for a reviewer to "
                    + "confirm (POST approve) or reclassify them. status is ignored then. reason keeps the records that "
                    + "need a person for that reason (reviewReasonCode of each item). q matches the ATIN, GTIN or title, or "
                    + "a seller named in qSellerOrgIds. sort is title, atin, category, status, submitted or reviewed, "
                    + "with order asc or desc. Every item names its sellers (sellerOrgIds) and why it needs a person "
                    + "(reviewReasonCode).")
    @GetMapping("/v1/tax/variant-classifications")
    VariantPage listVariants(@RequestParam(required = false) RecordStatus status,
                             @RequestParam(required = false) String categoryCode,
                             @RequestParam(required = false) String sellerOrgId, @RequestParam(required = false) String q,
                             @RequestParam(required = false) List<String> qSellerOrgIds,
                             @RequestParam(required = false) ReviewReason reason,
                             @RequestParam(required = false) String sort,
                             @RequestParam(defaultValue = "asc") String order,
                             @RequestParam(defaultValue = "false") boolean auditSample,
                             @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        int s = Math.min(Math.max(size, 1), 200);
        int p = Math.max(page, 0);
        VariantClassificationRepository.Filter filter = new VariantClassificationRepository.Filter(
                auditSample ? null : status, categoryCode, sellerOrgId, q, auditSample, reason, qSellerOrgIds, sort,
                "desc".equalsIgnoreCase(order));
        ReviewList result = variants.search(filter, p, s);
        Map<String, String> why = variants.reviewReasons(result.items());
        return new VariantPage(result.items().stream()
                .map(v -> VariantDto.light(v, why.get(v.atin()), result.sellers().get(v.atin()))).toList(), p, s,
                result.total());
    }

    @Operation(summary = "The classification record of one variant ATIN with its audit trail (tax:read)")
    @GetMapping("/v1/tax/variant-classifications/{atin}")
    VariantDto getVariant(@PathVariable String atin) {
        return VariantDto.of(variants.get(atin));
    }

    @Operation(summary = "Approve a classification after review (tax:classify-admin)",
            description = "422 tax.classification-not-resolvable unless the profile resolves to a chargeable decision. A "
                    + "REFINE_CODE profile needs the verified provider code in refinedProviderCodes; staff-owned facts "
                    + "(for example exemption_confirmed) are set here.")
    @PostMapping("/v1/tax/variant-classifications/{atin}/approve")
    VariantDto approve(@PathVariable String atin, @RequestBody(required = false) ApproveRequest body) {
        ApproveRequest b = body == null ? new ApproveRequest(null, null, null, null) : body;
        return VariantDto.of(variants.approve(atin, b.toCommand()));
    }

    @Operation(summary = "Ask the seller for more information before a decision (tax:classify-admin)",
            description = "The message is stored on the record, audited (INFORMATION_REQUESTED) and published in "
                    + "tax.classification.changed; it is cleared when the seller's facts change.")
    @PostMapping("/v1/tax/variant-classifications/{atin}/request-information")
    VariantDto requestInformation(@PathVariable String atin, @Valid @RequestBody InformationRequestBody body) {
        return VariantDto.of(variants.requestInformation(atin, body.message()));
    }

    @Operation(summary = "Move a variant to another category or product type, with a reason (tax:classify-admin)",
            description = "The previous decision stays in the audit trail; the record returns to review and "
                    + "tax.classification.changed is published.")
    @PostMapping("/v1/tax/variant-classifications/{atin}/reclassify")
    VariantDto reclassify(@PathVariable String atin, @Valid @RequestBody ReclassifyRequest body) {
        return VariantDto.of(variants.reclassify(atin, body.toCommand()));
    }

    @Tag(name = "Tax shadow")
    @Operation(summary = "Shadow mode report: provider quote vs in-house quote on real orders (tax:read)",
            description = "Lines where the two engines disagree on outcome or amount, grouped by internal tax code, "
                    + "category, outcome, state and kind, with disagreement rates. Shadow quotes are logged and never charged.")
    @GetMapping("/v1/tax/shadow/report")
    ShadowReportDto shadowReport(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                 @RequestParam(required = false) TaxProviderId provider,
                                 @RequestParam(defaultValue = "true") boolean onlyDifferences,
                                 @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return ShadowReportDto.of(shadow.report(from, to, provider, onlyDifferences, Math.max(page, 0),
                Math.min(Math.max(size, 1), 200)));
    }
}
