package com.afrotransact.deliveryruns.adapter.in.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.afrotransact.deliveryruns.application.AttributionService;
import com.afrotransact.deliveryruns.application.AttributionService.Report;
import com.afrotransact.deliveryruns.domain.UtmTags;
import com.afrotransact.deliveryruns.port.AttributionRepository.Row;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Attribution", description = "Visits and sign-ups per UTM source, medium and campaign for a location and "
        + "its runs (S262): first-party counters, no visitor is identified.")
@RestController
class AttributionController {

    private final AttributionService attribution;

    AttributionController(AttributionService attribution) {
        this.attribution = attribution;
    }

    @Operation(summary = "Count a visit that carried UTM tags (no sign-in)",
            description = "The shop front sends it once per visit to a location page or run link whose address has "
                    + "utm_* tags. Only the three tags are kept (cleaned to short lower-case tokens, at most 100 "
                    + "different sets per location and day, the rest counted as 'other'); no cookie, address or "
                    + "visitor id. A visit without tags is accepted and not counted. 404 for an unknown or inactive "
                    + "location.")
    @SecurityRequirements
    @PostMapping("/v1/public/locations/{slug}/visits")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void countVisit(@PathVariable String slug, @Valid @RequestBody VisitRequest body) {
        attribution.visit(slug, body.runId(), UtmTags.clean(body.utmSource(), body.utmMedium(), body.utmCampaign()));
    }

    @Operation(summary = "Attributed visits and sign-ups of a location, or of one of its runs (delivery-runs:read)",
            description = "Per UTM source, medium and campaign over the last days (default 90, at most 366), most "
                    + "visits first.")
    @GetMapping("/v1/locations/{id}/attribution")
    AttributionResponse report(@PathVariable UUID id, @RequestParam(required = false) UUID runId,
                               @RequestParam(defaultValue = "90") int days) {
        return AttributionResponse.of(attribution.report(id, runId, days));
    }

    @Schema(name = "VisitRequest")
    record VisitRequest(@Schema(description = "The run the visitor was looking at, if any") UUID runId,
                        @Size(max = 80) @Schema(example = "whatsapp") String utmSource,
                        @Size(max = 80) @Schema(example = "social") String utmMedium,
                        @Size(max = 120) @Schema(example = "delivery-run-bastrop-tx-20261024") String utmCampaign) {
    }

    @Schema(name = "AttributionRow")
    record RowResponse(String utmSource, String utmMedium, String utmCampaign, long visits, long signups) {

        static RowResponse of(Row r) {
            return new RowResponse(r.tags().source(), r.tags().medium(), r.tags().campaign(), r.visits(), r.signups());
        }
    }

    @Schema(name = "Attribution")
    record AttributionResponse(UUID locationId, @Schema(description = "Null for the whole location") UUID runId,
                               @Schema(description = "First day counted (UTC)") LocalDate since, long visits,
                               long signups, List<RowResponse> rows) {

        static AttributionResponse of(Report r) {
            return new AttributionResponse(r.locationId(), r.runId(), r.since(), r.visits(), r.signups(),
                    r.rows().stream().map(RowResponse::of).toList());
        }
    }
}
