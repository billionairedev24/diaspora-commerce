package com.afrotransact.deliveryruns.adapter.in.web;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.deliveryruns.adapter.in.web.PublicDtos.AvailableRunsResponse;
import com.afrotransact.deliveryruns.adapter.in.web.PublicDtos.LocationPageResponse;
import com.afrotransact.deliveryruns.adapter.in.web.PublicDtos.PublicLocationResponse;
import com.afrotransact.deliveryruns.adapter.in.web.PublicDtos.PublicRunResponse;
import com.afrotransact.deliveryruns.application.Advertorial;
import com.afrotransact.deliveryruns.application.LocationPage;
import com.afrotransact.deliveryruns.application.PublicRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Public runs", description = "No sign-in: run availability, location pages, advertorial data")
@SecurityRequirements
@RestController
class PublicRunController {

    private final PublicRunService publicRuns;
    private final Clock clock;

    PublicRunController(PublicRunService publicRuns, Clock clock) {
        this.publicRuns = publicRuns;
        this.clock = clock;
    }

    @Operation(summary = "Open runs covering an address (checkout, deliver-to targeting)",
            description = "Give a 5-digit zip or lat and lng. Polygon areas are matched with geo-service zone "
                    + "coverage, radius areas with geo-service distances. Earliest cutoff first; `full` runs have no "
                    + "room (checkout offers the next one). 502 when geo-service is unavailable.")
    @GetMapping("/v1/public/runs/available")
    AvailableRunsResponse available(
            @Parameter(description = "5-digit ZIP, e.g. 78602") @RequestParam(required = false) String zip,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @Parameter(description = "S323: a member's early access hours; announced runs opening within that time are "
                    + "listed (orders-service sends the hours of the customer's membership; a run only takes the "
                    + "order when its reservation names them too)")
            @RequestParam(defaultValue = "0") int earlyAccessHours) {
        int early = Math.max(0, Math.min(earlyAccessHours, 720));
        return new AvailableRunsResponse(publicRuns.available(zip, lat, lng, early).stream()
                .map(PublicRunResponse::of).toList());
    }

    @Operation(summary = "Active locations")
    @GetMapping("/v1/public/locations")
    List<PublicLocationResponse> locations() {
        return publicRuns.locations().stream().map(PublicLocationResponse::of).toList();
    }

    @Operation(summary = "Location page data: upcoming runs, WhatsApp community link, social and share links")
    @GetMapping("/v1/public/locations/{slug}")
    LocationPageResponse locationPage(@PathVariable String slug) {
        LocationPage page = publicRuns.locationPage(slug);
        return LocationPageResponse.of(page, Instant.now(clock), page.locationUrl() + "?run=");
    }

    @Operation(summary = "Advertorial data for a run (content-service, S263)",
            description = "Town, date, window, cutoff, fee, countdown target and attributed CTA URL. 404 for draft "
                    + "runs. Expire the advertorial at `expiresAt` (the cutoff).")
    @GetMapping("/v1/public/runs/{id}/advertorial")
    Advertorial advertorial(@PathVariable UUID id) {
        return publicRuns.advertorial(id);
    }
}
