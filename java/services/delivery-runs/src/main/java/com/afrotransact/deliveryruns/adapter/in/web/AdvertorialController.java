package com.afrotransact.deliveryruns.adapter.in.web;

import java.util.UUID;

import com.afrotransact.deliveryruns.adapter.in.web.AdvertorialDtos.AdvertorialRequest;
import com.afrotransact.deliveryruns.adapter.in.web.AdvertorialDtos.AdvertorialResponse;
import com.afrotransact.deliveryruns.adapter.in.web.AdvertorialDtos.EventRequest;
import com.afrotransact.deliveryruns.adapter.in.web.AdvertorialDtos.GenerateRequest;
import com.afrotransact.deliveryruns.adapter.in.web.AdvertorialDtos.ListResponse;
import com.afrotransact.deliveryruns.adapter.in.web.AdvertorialDtos.ServedListResponse;
import com.afrotransact.deliveryruns.application.AdvertorialService;
import com.afrotransact.deliveryruns.application.AdvertorialServing;
import com.afrotransact.deliveryruns.application.AdvertorialServing.AdvertorialQuery;
import com.afrotransact.deliveryruns.application.AdvertorialView;
import com.afrotransact.deliveryruns.domain.AdvertorialSource;
import com.afrotransact.deliveryruns.domain.AdvertorialStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Advertorials", description = "Location-targeted advertorials per pre-order window or delivery run: staff "
        + "generate or write them, visitors in the area see them with a live closing countdown, and they report "
        + "views, clicks and orders (S263). delivery-runs:read to view; preorders:manage to change.")
@RestController
class AdvertorialController {

    private final AdvertorialService advertorials;
    private final AdvertorialServing serving;

    AdvertorialController(AdvertorialService advertorials, AdvertorialServing serving) {
        this.advertorials = advertorials;
        this.serving = serving;
    }

    @Operation(summary = "Advertorials, newest first, with views, clicks and orders (delivery-runs:read)")
    @GetMapping("/v1/advertorials")
    ListResponse listAdvertorials(@RequestParam(required = false) AdvertorialSource source,
                      @RequestParam(required = false) UUID sourceId,
                      @RequestParam(required = false) AdvertorialStatus status,
                      @RequestParam(defaultValue = "100") int limit) {
        return ListResponse.of(advertorials.list(source, sourceId, status, limit));
    }

    @Operation(summary = "One advertorial with its results (delivery-runs:read)")
    @GetMapping("/v1/advertorials/{id}")
    ResponseEntity<AdvertorialResponse> getAdvertorial(@PathVariable UUID id) {
        return tagged(advertorials.get(id));
    }

    @Operation(summary = "Draft the advertorial of a window or run from its data (preorders:manage)",
            description = "Town, delivery day, closing time and fee become the words, live at once. Asking again "
                    + "re-drafts the generated one. 404 for an unknown window or run; 409 for one that is over.")
    @PostMapping("/v1/advertorials/generate")
    ResponseEntity<AdvertorialResponse> generateAdvertorial(@Valid @RequestBody GenerateRequest body) {
        return tagged(advertorials.generate(body.source(), body.sourceId()));
    }

    @Operation(summary = "Write an advertorial for a window or run (preorders:manage)",
            description = "Created as a draft; publish it to make it live. Several variants (A, B…) of one window or "
                    + "run share its visitors by weight.")
    @PostMapping("/v1/advertorials")
    @ResponseStatus(HttpStatus.CREATED)
    AdvertorialResponse createAdvertorial(@Valid @RequestBody AdvertorialRequest body) {
        return AdvertorialResponse.of(advertorials.create(body.toCommand()));
    }

    @Operation(summary = "Edit an advertorial (preorders:manage)",
            description = "Not an ended one. The words stop following the window or run. If-Match is the version "
                    + "seen (412 delivery-runs.version-mismatch when it changed).")
    @PutMapping("/v1/advertorials/{id}")
    ResponseEntity<AdvertorialResponse> updateAdvertorial(@PathVariable UUID id, @Valid @RequestBody AdvertorialRequest body,
                                               @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                                               String ifMatch) {
        return tagged(advertorials.update(id, body.toCommand(), Versions.expected(ifMatch)));
    }

    @Operation(summary = "Draft or paused to live (preorders:manage)")
    @PostMapping("/v1/advertorials/{id}/publish")
    ResponseEntity<AdvertorialResponse> publishAdvertorial(@PathVariable UUID id,
                                                @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                                                String ifMatch) {
        return tagged(advertorials.publish(id, Versions.expected(ifMatch)));
    }

    @Operation(summary = "Live to paused (preorders:manage)")
    @PostMapping("/v1/advertorials/{id}/pause")
    ResponseEntity<AdvertorialResponse> pauseAdvertorial(@PathVariable UUID id,
                                              @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                                              String ifMatch) {
        return tagged(advertorials.pause(id, Versions.expected(ifMatch)));
    }

    @Operation(summary = "End an advertorial for good (preorders:manage)")
    @PostMapping("/v1/advertorials/{id}/end")
    ResponseEntity<AdvertorialResponse> endAdvertorial(@PathVariable UUID id,
                                            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                                            String ifMatch) {
        return tagged(advertorials.end(id, Versions.expected(ifMatch)));
    }

    @Operation(summary = "The advertorials a visitor sees for their deliver-to place (no sign-in)",
            description = "A ZIP or coordinates match delivery runs by coverage and the pre-order windows of the towns "
                    + "that coverage falls in (a ZIP alone is enough); country and region (and city) match "
                    + "pre-order windows too. Only windows and runs that take orders right now are served, one variant "
                    + "each (the same visitor keeps their variant), higher priority first. Each carries the "
                    + "countdown target (the close or cutoff) and a link with UTM and order attribution.")
    @SecurityRequirements
    @GetMapping("/v1/public/advertorials")
    ServedListResponse servedAdvertorials(@RequestParam(required = false) String zip, @RequestParam(required = false) Double lat,
                              @RequestParam(required = false) Double lng,
                              @RequestParam(required = false) String country,
                              @RequestParam(required = false) String region,
                              @RequestParam(required = false) String city,
                              @RequestParam(required = false) String visitor,
                              @RequestParam(defaultValue = "3") int limit) {
        return ServedListResponse.of(serving.serve(new AdvertorialQuery(zip, lat, lng, country, region, city,
                visitor, limit)));
    }

    @Operation(summary = "Count a view or a click (no sign-in)",
            description = "Best effort: the shop front sends VIEW once per visit and CLICK on the button. 404 for an "
                    + "unknown advertorial. 204 without counting when the advertorial is not live and servable now "
                    + "(draft, paused, ended, outside its schedule, or its window or run is closed) or when the same "
                    + "visitor (or client address) already counted this kind in the event window.")
    @SecurityRequirements
    @PostMapping("/v1/public/advertorials/{id}/events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void advertorialEvent(@PathVariable UUID id, @Valid @RequestBody EventRequest body, HttpServletRequest request) {
        serving.record(id, body.type(), body.visitor(), clientAddress(request));
    }

    /** The first hop of {@code X-Forwarded-For} (the gateway sets it), else the socket's remote address. */
    private static String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static ResponseEntity<AdvertorialResponse> tagged(AdvertorialView view) {
        return Versions.tagged(AdvertorialResponse.of(view), view.advertorial().version());
    }
}
