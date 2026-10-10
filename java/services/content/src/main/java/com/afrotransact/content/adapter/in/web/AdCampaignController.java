package com.afrotransact.content.adapter.in.web;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.afrotransact.content.adapter.in.web.AdDtos.CampaignRequest;
import com.afrotransact.content.adapter.in.web.AdDtos.CampaignResponse;
import com.afrotransact.content.adapter.in.web.AdDtos.ReportResponse;
import com.afrotransact.content.application.AdCampaignService;
import com.afrotransact.content.domain.ads.CampaignStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Sponsored products", description = "Seller campaigns: CPC bids, budgets, targeting, reports (S127)")
@RestController
@RequestMapping("/v1/ads/campaigns")
class AdCampaignController {

    private final AdCampaignService campaigns;
    private final Clock clock;

    AdCampaignController(AdCampaignService campaigns, Clock clock) {
        this.campaigns = campaigns;
        this.clock = clock;
    }

    @Operation(summary = "List campaigns (sellers: their organisation's with ads:manage; staff: content:ads.view)")
    @GetMapping
    List<CampaignResponse> list(@RequestParam(required = false) String sellerOrgId,
                                @RequestParam(required = false) CampaignStatus status) {
        return campaigns.list(sellerOrgId, status).stream().map(CampaignResponse::of).toList();
    }

    @Operation(summary = "Get a campaign")
    @GetMapping("/{id}")
    CampaignResponse get(@PathVariable String id) {
        return CampaignResponse.of(campaigns.get(id));
    }

    @Operation(summary = "Create a campaign (ACTIVE from startsAt; ads:manage for the organisation, or content:ads.edit)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CampaignResponse create(@Valid @RequestBody CampaignRequest body) {
        return CampaignResponse.of(campaigns.create(body.sellerOrgId(), body.toContent(Instant.now(clock))));
    }

    @Operation(summary = "Edit bids, budgets, targeting or schedule (expectedVersion required); raising the budget of "
            + "a campaign paused at zero resumes it")
    @PutMapping("/{id}")
    CampaignResponse update(@PathVariable String id, @Valid @RequestBody CampaignRequest body) {
        if (body.expectedVersion() == null) {
            throw new IllegalArgumentException("expectedVersion is required");
        }
        return CampaignResponse.of(campaigns.update(id, body.toContent(Instant.now(clock)), body.expectedVersion()));
    }

    @Operation(summary = "Pause (publishes content.ads.campaign_paused)")
    @PostMapping("/{id}/pause")
    CampaignResponse pause(@PathVariable String id) {
        return CampaignResponse.of(campaigns.pause(id));
    }

    @Operation(summary = "Resume a paused campaign (409 content.ads-budget-exhausted when nothing is left)")
    @PostMapping("/{id}/resume")
    CampaignResponse resume(@PathVariable String id) {
        return CampaignResponse.of(campaigns.resume(id));
    }

    @Operation(summary = "End the campaign for good")
    @PostMapping("/{id}/end")
    CampaignResponse end(@PathVariable String id) {
        return CampaignResponse.of(campaigns.end(id));
    }

    @Operation(summary = "Daily report: impressions, clicks, CTR, spend, CPC, attributed orders and sales (7-day "
            + "click), ROAS (default: last 30 days)")
    @GetMapping("/{id}/report")
    ReportResponse report(@PathVariable String id,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ReportResponse.of(campaigns.report(id, from, to));
    }
}
