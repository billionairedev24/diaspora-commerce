package com.afrotransact.content.adapter.in.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.afrotransact.content.application.CampaignReport;
import com.afrotransact.content.application.ServableCampaign;
import com.afrotransact.content.domain.ads.Campaign;
import com.afrotransact.content.domain.ads.CampaignContent;
import com.afrotransact.content.domain.ads.CampaignDay;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Request and response bodies of the sponsored-products endpoints. */
final class AdDtos {

    private AdDtos() {
    }

    @Schema(name = "AdCampaignRequest")
    record CampaignRequest(
            @Schema(description = "Advertiser organisation (sellers: their own; required on create)") String sellerOrgId,
            String name,
            @Schema(description = "Shown with the ad: Sponsored · advertiser") String advertiser,
            @Schema(example = "US") String market,
            @NotNull @Schema(description = "Maximum cost per click (minor units, ≥ 10)") Long bidMinor,
            @Schema(description = "Optional daily cap (minor units)") Long dailyBudgetMinor,
            @NotNull @Schema(description = "Lifetime budget (minor units, ≥ 500); the campaign pauses when spent")
            Long totalBudgetMinor,
            @Schema(example = "USD") String currency,
            @Schema(description = "Search phrases; every word must be in the query") List<String> keywords,
            @Schema(description = "Departments or category paths, e.g. \"Groceries › Pantry\"") List<String> categories,
            @Schema(description = "Advertised products; the seller must hold their buy box") List<String> atins,
            Instant startsAt, Instant endsAt,
            @Schema(description = "Required on update: the version that was loaded") Long expectedVersion) {

        CampaignContent toContent(Instant now) {
            return new CampaignContent(name, advertiser, market == null ? "US" : market, bidMinor, dailyBudgetMinor,
                    totalBudgetMinor, currency == null ? "USD" : currency, keywords, categories, atins,
                    startsAt == null ? now : startsAt, endsAt);
        }
    }

    @Schema(name = "AdCampaign")
    record CampaignResponse(String id, String sellerOrgId, String name, String advertiser, String market,
                            @Schema(allowableValues = {"ACTIVE", "PAUSED", "ENDED"}) String status,
                            @Schema(allowableValues = {"MANUAL", "BUDGET_EXHAUSTED"}) String pauseReason,
                            long bidMinor, Long dailyBudgetMinor, long totalBudgetMinor, long spentMinor,
                            long remainingMinor, String currency, List<String> keywords, List<String> categories,
                            List<String> atins, Instant startsAt, Instant endsAt, String createdBy, Instant createdAt,
                            Instant updatedAt, long version) {

        static CampaignResponse of(Campaign c) {
            CampaignContent k = c.content();
            return new CampaignResponse(c.id(), c.sellerOrgId(), k.name(), k.advertiser(), k.market(), c.status().name(),
                    c.pauseReason() == null ? null : c.pauseReason().name(), k.bidMinor(), k.dailyBudgetMinor(),
                    k.totalBudgetMinor(), c.spentMinor(), c.remainingMinor(), k.currency(), k.keywords(), k.categories(),
                    k.atins(), k.startsAt(), k.endsAt(), c.createdBy(), c.createdAt(), c.updatedAt(), c.version());
        }
    }

    @Schema(name = "AdCampaignDay")
    record DayResponse(@Schema(description = "UTC day; null on the totals row") LocalDate day, long impressions,
                       long clicks, double ctr, long spendMinor, long cpcMinor, long attributedOrders,
                       long attributedSalesMinor, double roas) {

        static DayResponse of(CampaignDay d) {
            return new DayResponse(d.day(), d.impressions(), d.clicks(), d.ctr(), d.spendMinor(), d.cpcMinor(),
                    d.attributedOrders(), d.attributedSalesMinor(), d.roas());
        }
    }

    @Schema(name = "AdCampaignReport", description = "Impressions, clicks, spend and 7-day-click attributed sales")
    record ReportResponse(String campaignId, String currency, LocalDate from, LocalDate to, List<DayResponse> days,
                          DayResponse total, long spentMinor, long remainingMinor) {

        static ReportResponse of(CampaignReport r) {
            return new ReportResponse(r.campaignId(), r.currency(), r.from(), r.to(),
                    r.days().stream().map(DayResponse::of).toList(), DayResponse.of(r.total()), r.spentMinor(),
                    r.remainingMinor());
        }
    }

    @Schema(name = "ServableAdCampaign")
    record ServableResponse(String id, String sellerOrgId, String advertiser, long bidMinor, long remainingMinor,
                            String currency, List<String> keywords, List<String> categories, List<String> atins) {

        static ServableResponse of(ServableCampaign c) {
            return new ServableResponse(c.id(), c.sellerOrgId(), c.advertiser(), c.bidMinor(), c.remainingMinor(),
                    c.currency(), c.keywords(), c.categories(), c.atins());
        }
    }

    @Schema(name = "AdServingResponse")
    record ServingResponse(String market, List<ServableResponse> campaigns) {
    }
}
