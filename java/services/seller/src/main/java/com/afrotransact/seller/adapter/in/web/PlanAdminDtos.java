package com.afrotransact.seller.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.afrotransact.seller.application.MigrationPreview;
import com.afrotransact.seller.application.MigrationResult;
import com.afrotransact.seller.application.PlanAdminView;
import com.afrotransact.seller.domain.CampaignInput;
import com.afrotransact.seller.domain.CampaignStatus;
import com.afrotransact.seller.domain.OrgKind;
import com.afrotransact.seller.domain.Plan;
import com.afrotransact.seller.domain.PlanEntitlements;
import com.afrotransact.seller.domain.PlanInput;
import com.afrotransact.seller.domain.PlanPricesInput;
import com.afrotransact.seller.domain.PlanVersion;
import com.afrotransact.seller.domain.PlanVisibility;
import com.afrotransact.seller.domain.TrialCampaign;
import com.afrotransact.seller.port.PlanRepository;
import io.swagger.v3.oas.annotations.media.Schema;

/** Staff plan catalogue, migrations and trial campaigns. */
final class PlanAdminDtos {

    private PlanAdminDtos() {
    }

    @Schema(name = "PlanPrices", description = "A version's price per cadence (cents) and default trial")
    record PricesBody(Long monthlyCents, Long quarterlyCents, Long annualCents, Integer trialDays, String note) {

        PlanPricesInput toInput() {
            return new PlanPricesInput(monthlyCents, quarterlyCents, annualCents, trialDays, note);
        }
    }

    @Schema(name = "CreatePlanRequest")
    record CreatePlanBody(String code, @Schema(allowableValues = {"SELLER", "PROVIDER"}) String kind, String name,
                          String tagline, List<String> features, Map<String, String> limits,
                          @Schema(allowableValues = {"PUBLIC", "HIDDEN", "INVITE_ONLY"}) String visibility,
                          Integer sortOrder, PricesBody prices) {

        PlanInput toInput() {
            return new PlanInput(code, Inputs.kind(kind), name, tagline, features, limits,
                    Inputs.enumOf(PlanVisibility.class, visibility, "visibility"), sortOrder);
        }
    }

    @Schema(name = "UpdatePlanRequest", description = "Metadata only; expectedVersion is the plan's rowVersion")
    record UpdatePlanBody(String name, String tagline, List<String> features, Map<String, String> limits,
                          @Schema(allowableValues = {"PUBLIC", "HIDDEN", "INVITE_ONLY"}) String visibility,
                          Integer sortOrder, long expectedVersion) {

        PlanInput toInput() {
            return new PlanInput(null, null, name, tagline, features, limits,
                    Inputs.enumOf(PlanVisibility.class, visibility, "visibility"), sortOrder);
        }
    }

    @Schema(name = "PlanInvitesRequest")
    record InvitesBody(List<String> orgIds) {
    }

    @Schema(name = "PlanVersionPrice", description = "One cadence of a version")
    record VersionPriceView(String cadence, long amountCents) {

        static VersionPriceView of(PlanRepository.PriceRow p) {
            return new VersionPriceView(p.cadence().name(), p.amountCents());
        }
    }

    @Schema(name = "PlanVersion")
    record VersionView(int version, @Schema(allowableValues = {"DRAFT", "PUBLISHED", "SUPERSEDED"}) String status,
                       int trialDays, String note, String createdBy, Instant createdAt, Instant publishedAt,
                       long subscribers, List<VersionPriceView> prices) {

        static VersionView of(PlanAdminView.VersionView v) {
            PlanVersion pv = v.version();
            return new VersionView(pv.version(), pv.status().name(), pv.trialDays(), pv.note(), pv.createdBy(),
                    pv.createdAt(), pv.publishedAt(), v.subscribers(),
                    v.prices().stream().map(VersionPriceView::of).toList());
        }
    }

    @Schema(name = "PlanAdmin", description = "A plan for staff: metadata, versions with prices, invites")
    record PlanAdminResponse(String code, String kind, String name, String tagline, List<String> features,
                             Map<String, String> limits, BillingDtos.EntitlementsView entitlements, String visibility,
                             String status, int sortOrder,
                             int currentVersion, long monthlyCents, long quarterlyCents, long annualCents,
                             int trialDays, long rowVersion, long subscribers,
                             List<String> invites, List<VersionView> versions) {

        static PlanAdminResponse of(PlanAdminView v) {
            Plan p = v.plan();
            return new PlanAdminResponse(p.code(), p.kind().name(), p.name(), p.tagline(), p.features(), p.limits(),
                    BillingDtos.EntitlementsView.of(PlanEntitlements.of(p.limits())), p.visibility().name(),
                    p.status().name(), p.sortOrder(), p.version(), p.monthlyCents(), p.quarterlyCents(),
                    p.annualCents(), p.trialDays(), v.rowVersion(),
                    v.subscribers(), v.invites(), v.versions().stream().map(VersionView::of).toList());
        }
    }

    @Schema(name = "PlanMigrationPreview")
    record MigrationPreviewResponse(String planCode, int toVersion, List<MigrationRowView> rows) {

        static MigrationPreviewResponse of(MigrationPreview p) {
            return new MigrationPreviewResponse(p.planCode(), p.toVersion(), p.rows().stream()
                    .map(r -> new MigrationRowView(r.orgId(), r.orgName(), r.fromVersion(), r.cadence().name(),
                            r.fromCents(), r.toCents(), r.status(), r.effectiveAt())).toList());
        }
    }

    @Schema(name = "PlanMigrationRow")
    record MigrationRowView(String orgId, String orgName, int fromVersion, String cadence, long fromCents,
                            long toCents, String status, Instant effectiveAt) {
    }

    @Schema(name = "PlanMigrationRequest", description = "orgIds empty or missing = every subscriber in the preview")
    record MigrationBody(List<String> orgIds) {
    }

    @Schema(name = "PlanMigrationResult")
    record MigrationResultResponse(String planCode, int toVersion, List<String> migrated,
                                   List<MigrationFailureView> failed) {

        static MigrationResultResponse of(MigrationResult r) {
            return new MigrationResultResponse(r.planCode(), r.toVersion(), r.migrated(), r.failed().stream()
                    .map(f -> new MigrationFailureView(f.orgId(), f.error())).toList());
        }
    }

    @Schema(name = "PlanMigrationFailure")
    record MigrationFailureView(String orgId, String error) {
    }

    @Schema(name = "TrialCampaignRequest", description = "A promo code, a sign-up window, or both")
    record CampaignBody(String name, String promoCode, Integer trialDays, Instant signupFrom, Instant signupTo,
                        @Schema(allowableValues = {"SELLER", "PROVIDER"}) String kind, List<String> eligiblePlans,
                        Integer maxRedemptions,
                        @Schema(allowableValues = {"ACTIVE", "PAUSED", "ENDED"}) String status,
                        Long expectedVersion) {

        CampaignInput toInput() {
            return new CampaignInput(name, promoCode, trialDays, signupFrom, signupTo,
                    kind == null ? null : Inputs.kind(kind), eligiblePlans, maxRedemptions,
                    Inputs.enumOf(CampaignStatus.class, status, "status"));
        }
    }

    @Schema(name = "StatusRequest")
    record StatusBody(String status) {
    }

    @Schema(name = "TrialCampaign")
    record CampaignResponse(String id, String name, String promoCode, int trialDays, Instant signupFrom,
                            Instant signupTo, String kind, List<String> eligiblePlans, Integer maxRedemptions,
                            int redemptions, String status, String createdBy, Instant createdAt, String updatedBy,
                            Instant updatedAt, long version) {

        static CampaignResponse of(TrialCampaign c) {
            OrgKind k = c.kind();
            return new CampaignResponse(c.id().toString(), c.name(), c.promoCode(), c.trialDays(), c.signupFrom(),
                    c.signupTo(), k == null ? null : k.name(), c.eligiblePlans(), c.maxRedemptions(),
                    c.redemptions(), c.status().name(), c.createdBy(), c.createdAt(), c.updatedBy(), c.updatedAt(),
                    c.version());
        }
    }
}
