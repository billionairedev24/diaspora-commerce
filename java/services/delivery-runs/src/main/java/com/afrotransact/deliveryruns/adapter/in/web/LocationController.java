package com.afrotransact.deliveryruns.adapter.in.web;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.deliveryruns.adapter.in.web.LocationDtos.DeactivateRequest;
import com.afrotransact.deliveryruns.adapter.in.web.LocationDtos.LocationHistoryResponse;
import com.afrotransact.deliveryruns.adapter.in.web.LocationDtos.LocationRequest;
import com.afrotransact.deliveryruns.adapter.in.web.LocationDtos.LocationResponse;
import com.afrotransact.deliveryruns.adapter.in.web.LocationDtos.SubscriberCountsResponse;
import com.afrotransact.deliveryruns.adapter.in.web.LocationDtos.SwitchRequest;
import com.afrotransact.deliveryruns.application.LocationService;
import com.afrotransact.deliveryruns.application.SubscriptionService;
import com.afrotransact.deliveryruns.domain.Channel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Locations", description = "Staff: towns / areas served by delivery runs (delivery-runs:read / :manage)")
@RestController
class LocationController {

    private final LocationService locations;
    private final SubscriptionService subscriptions;

    LocationController(LocationService locations, SubscriptionService subscriptions) {
        this.locations = locations;
        this.subscriptions = subscriptions;
    }

    @Operation(summary = "Create a location (delivery-runs:manage)")
    @PostMapping("/v1/locations")
    @ResponseStatus(HttpStatus.CREATED)
    LocationResponse create(@Valid @RequestBody LocationRequest body) {
        return LocationResponse.of(locations.create(body.toCommand()));
    }

    @Operation(summary = "List locations, optionally only active or inactive ones (delivery-runs:read)")
    @GetMapping("/v1/locations")
    List<LocationResponse> list(@RequestParam(required = false) Boolean active) {
        return locations.list(active).stream().map(LocationResponse::of).toList();
    }

    @Operation(summary = "Get a location (delivery-runs:read)")
    @GetMapping("/v1/locations/{id}")
    LocationResponse get(@PathVariable UUID id) {
        return LocationResponse.of(locations.get(id));
    }

    @Operation(summary = "Replace a location's details (delivery-runs:manage)")
    @PutMapping("/v1/locations/{id}")
    LocationResponse update(@PathVariable UUID id, @Valid @RequestBody LocationRequest body) {
        return LocationResponse.of(locations.update(id, body.toCommand()));
    }

    @Operation(summary = "Deactivate a location (never deleted: runs and consent records refer to it)",
            description = "delivery-runs:manage. Inactive locations get no new runs and disappear from public reads.")
    @DeleteMapping("/v1/locations/{id}")
    LocationResponse deactivate(@PathVariable UUID id) {
        return LocationResponse.of(locations.deactivate(id));
    }

    @Operation(summary = "Switch a location off with the reason that is audited (delivery-runs:manage)",
            description = "The reason is required and recorded with who and when (event delivery-runs.location.changed, "
                    + "audit log). 409 delivery-runs.location-has-live-runs while a run of the location announced, "
                    + "takes orders or is on the road. Already inactive: unchanged.")
    @PostMapping("/v1/locations/{id}/deactivate")
    LocationResponse deactivateWithReason(@PathVariable UUID id, @Valid @RequestBody DeactivateRequest body) {
        return LocationResponse.of(locations.deactivate(id, body.reason()));
    }

    @Operation(summary = "Switch a deactivated location back on (delivery-runs:manage)",
            description = "The optional reason is audited like the switch off. Already active: unchanged.")
    @PostMapping("/v1/locations/{id}/activate")
    LocationResponse activate(@PathVariable UUID id, @Valid @RequestBody(required = false) SwitchRequest body) {
        return LocationResponse.of(locations.activate(id, body == null ? null : body.reason()));
    }

    @Operation(summary = "Per-location history: runs with orders, cost, margin and break-even, windows, subscribers and growth",
            description = "delivery-runs:read (S265). Runs are counted and priced as their P&L is; windows list their "
                    + "orders, delivery fees, refunds and whether the minimum was met; growth is monthly, gross and net "
                    + "of unsubscribes.")
    @GetMapping("/v1/locations/{id}/history")
    LocationHistoryResponse history(@PathVariable UUID id) {
        return LocationHistoryResponse.of(locations.history(id));
    }

    @Operation(summary = "Subscriber counts of a location, per channel (delivery-runs:read)")
    @GetMapping("/v1/locations/{id}/subscribers/counts")
    SubscriberCountsResponse counts(@PathVariable UUID id) {
        return subscriptions.counts(id).stream().findFirst().map(SubscriberCountsResponse::of)
                .orElseGet(() -> new SubscriberCountsResponse(id, 0, 0, 0, emptyChannels()));
    }

    @Operation(summary = "Subscriber counts of every location, per channel (delivery-runs:read)")
    @GetMapping("/v1/subscribers/counts")
    List<SubscriberCountsResponse> allCounts() {
        return subscriptions.counts(null).stream().map(SubscriberCountsResponse::of).toList();
    }

    private static Map<Channel, Long> emptyChannels() {
        Map<Channel, Long> zero = new EnumMap<>(Channel.class);
        for (Channel c : Channel.values()) {
            zero.put(c, 0L);
        }
        return zero;
    }
}
