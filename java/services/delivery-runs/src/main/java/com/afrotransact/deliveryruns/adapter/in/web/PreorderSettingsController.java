package com.afrotransact.deliveryruns.adapter.in.web;

import com.afrotransact.deliveryruns.adapter.in.web.PreorderDtos.AreaRequest;
import com.afrotransact.deliveryruns.adapter.in.web.PreorderDtos.AreaToggleRequest;
import com.afrotransact.deliveryruns.adapter.in.web.PreorderDtos.RegionCoverageRequest;
import com.afrotransact.deliveryruns.adapter.in.web.PreorderDtos.SettingsRequest;
import com.afrotransact.deliveryruns.adapter.in.web.PreorderDtos.SettingsResponse;
import com.afrotransact.deliveryruns.application.AreaPage;
import com.afrotransact.deliveryruns.application.PreorderSettingsService;
import com.afrotransact.deliveryruns.application.PreorderSettingsService.PlaceList;
import com.afrotransact.deliveryruns.application.RegionCoverageView;
import com.afrotransact.deliveryruns.domain.PreorderSettings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Pre-order settings", description = "Staff: master switch, fallback, placements, payment setup and "
        + "coverage areas (delivery-runs:read to view; preorders:manage to change)")
@RestController
class PreorderSettingsController {

    private final PreorderSettingsService settings;

    PreorderSettingsController(PreorderSettingsService settings) {
        this.settings = settings;
    }

    @Operation(summary = "Platform pre-order settings incl. payment setup (delivery-runs:read)",
            description = "ETag carries the version to send back in If-Match.")
    @GetMapping("/v1/preorder-settings")
    ResponseEntity<SettingsResponse> get() {
        PreorderSettings s = settings.get();
        return Versions.tagged(SettingsResponse.of(s), s.version());
    }

    @Operation(summary = "Change settings (preorders:manage)",
            description = "Null fields keep their value; placements may name only the ones that change. Fees 0–100.00 "
                    + "per currency, at least one payment method (422 otherwise). If-Match; publishes "
                    + "preorder.settings.changed.")
    @PutMapping("/v1/preorder-settings")
    ResponseEntity<SettingsResponse> update(@Valid @RequestBody SettingsRequest body,
                                            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                                            String ifMatch) {
        PreorderSettings current = settings.get();
        PreorderSettings saved = settings.update(body.toCommand(current), Versions.expected(ifMatch));
        return Versions.tagged(SettingsResponse.of(saved), saved.version());
    }

    @Operation(summary = "Coverage drill-down: countries → regions → areas, 12 per page (delivery-runs:read)",
            description = "level COUNTRY (no country), REGION (country) or AREA (country + region). Rows carry areas "
                    + "on of total and the published pre-orders targeting them; saved lists every area switched on.")
    @GetMapping("/v1/preorder-areas")
    AreaPage areas(@RequestParam(required = false) String country, @RequestParam(required = false) String region,
                   @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
                   @RequestParam(defaultValue = "12") int size) {
        return settings.areas(country, region, q, page, size);
    }

    @Operation(summary = "Add a coverage area, switched on (preorders:manage)",
            description = "key: country/region (every city) or country/region/city; 409 when it exists. Returns the "
                    + "page of the area's region.")
    @PostMapping("/v1/preorder-areas")
    @ResponseStatus(HttpStatus.CREATED)
    AreaPage add(@Valid @RequestBody AreaRequest body) {
        return settings.addArea(body.key());
    }

    @Operation(summary = "Switch areas on or off in bulk (preorders:manage)",
            description = "Every area at or under each key: a country code, a region key or one area. Returns the page "
                    + "of the first key's row.")
    @PatchMapping("/v1/preorder-areas")
    AreaPage toggle(@Valid @RequestBody AreaToggleRequest body) {
        return settings.toggle(body.keys(), body.on());
    }

    @Operation(summary = "Remove a coverage area (preorders:manage)",
            description = "The key is the rest of the path (DELETE /v1/preorder-areas/US/TX/El%20Paso; an encoded "
                    + "US%2FTX%2FEl%20Paso works too). Returns the page of the area's region. 409 "
                    + "delivery-runs.area-in-use when a live pre-order sells there and nothing else covers it.")
    @DeleteMapping("/v1/preorder-areas/{*key}")
    AreaPage remove(@PathVariable String key) {
        return settings.removeArea(key.startsWith("/") ? key.substring(1) : key);
    }

    @Operation(summary = "Remove a coverage area named by ?key= (preorders:manage)")
    @DeleteMapping("/v1/preorder-areas")
    AreaPage removeByQuery(@RequestParam String key) {
        return settings.removeArea(key);
    }

    @Operation(summary = "One region's coverage for the in-place editor (delivery-runs:read)",
            description = "The \"Every city\" row (or null) and every listed city with its switch, row version and the "
                    + "live pre-orders selling there. ETag = the region's version (send it back as If-Match).")
    @GetMapping("/v1/preorder-areas/regions/{country}/{region}")
    ResponseEntity<RegionCoverageView> region(@PathVariable String country, @PathVariable String region) {
        RegionCoverageView v = settings.region(country, region);
        return Versions.tagged(v, v.version());
    }

    @Operation(summary = "Edit one region's coverage in place (preorders:manage)",
            description = "Full replacement of the region's rows: cities listed are added or switched, cities left out "
                    + "are removed, everyCity adds, switches or (null) removes the \"Every city\" row. If-Match with "
                    + "the region's ETag: 412 delivery-runs.version-mismatch when anyone changed the region since. "
                    + "409 delivery-runs.area-in-use when a removal would leave a live pre-order's place uncovered "
                    + "(switch it off instead). Publishes preorder.areas.changed with the actor.")
    @PutMapping("/v1/preorder-areas/regions/{country}/{region}")
    ResponseEntity<RegionCoverageView> updateRegion(@PathVariable String country, @PathVariable String region,
                                                    @Valid @RequestBody RegionCoverageRequest body,
                                                    @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                                                    String ifMatch) {
        RegionCoverageView v = settings.updateRegion(body.toCoverage(country, region), Versions.expected(ifMatch));
        return Versions.tagged(v, v.version());
    }

    @Operation(summary = "Places for the area pickers (delivery-runs:read)",
            description = "q (2+ characters): search countries, regions and cities (8 best). Otherwise browse: every "
                    + "country, a country's regions (country) or every city of a region (country + region), from "
                    + "geo-service's world gazetteer (built-in list when geo is down). regionLabel is what the country "
                    + "calls regions.")
    @GetMapping("/v1/places/search")
    PlaceList places(@RequestParam(required = false) String country, @RequestParam(required = false) String region,
                     @RequestParam(required = false) String q) {
        return settings.places(country, region, q);
    }
}
