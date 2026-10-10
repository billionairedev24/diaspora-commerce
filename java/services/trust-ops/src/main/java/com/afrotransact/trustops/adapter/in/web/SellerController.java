package com.afrotransact.trustops.adapter.in.web;

import java.util.List;

import com.afrotransact.trustops.adapter.in.web.SellerDtos.HealthResponse;
import com.afrotransact.trustops.adapter.in.web.SellerDtos.ReinstateRequest;
import com.afrotransact.trustops.adapter.in.web.SellerDtos.SellerHealthResponse;
import com.afrotransact.trustops.adapter.in.web.SellerDtos.StandingResponse;
import com.afrotransact.trustops.adapter.in.web.SellerDtos.SuspendRequest;
import com.afrotransact.trustops.application.SellerEnforcementService;
import com.afrotransact.trustops.application.SellerHealthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Seller health & enforcement", description = "Late shipment, cancellation and defect rates; suspend and "
        + "reinstate sellers")
@RestController
class SellerController {

    private final SellerHealthService health;
    private final SellerEnforcementService enforcement;

    SellerController(SellerHealthService health, SellerEnforcementService enforcement) {
        this.health = health;
        this.enforcement = enforcement;
    }

    @Operation(summary = "Seller health over the rolling window, with standing (orgs:read)")
    @GetMapping("/v1/seller-health/{orgId}")
    SellerHealthResponse health(@PathVariable String orgId) {
        return SellerHealthResponse.of(health.health(orgId));
    }

    @Operation(summary = "Recompute now, store a snapshot and push performance to offers (sellers:enforce)")
    @PostMapping("/v1/seller-health/{orgId}/recompute")
    HealthResponse recompute(@PathVariable String orgId) {
        return HealthResponse.of(health.recompute(orgId));
    }

    @Operation(summary = "Seller standing (orgs:read)")
    @GetMapping("/v1/seller-enforcement/{orgId}")
    StandingResponse standing(@PathVariable String orgId) {
        return StandingResponse.of(enforcement.standing(orgId));
    }

    @Operation(summary = "Suspended sellers (orgs:read)")
    @GetMapping("/v1/seller-enforcement")
    List<StandingResponse> suspended() {
        return enforcement.suspended().stream().map(StandingResponse::of).toList();
    }

    @Operation(summary = "Suspend: seller-service suspension hold (payouts paused, listings hidden) and buy-box "
            + "restriction (offers); audited. Calling again retries steps that failed (sellers:enforce)",
            description = "AfroTransact Retail (the first-party merchant of record) is exempt: 422 "
                    + "trustops.retail-exempt.")
    @PostMapping("/v1/seller-enforcement/{orgId}/suspend")
    StandingResponse suspend(@PathVariable String orgId, @Valid @RequestBody SuspendRequest body) {
        return StandingResponse.of(enforcement.suspend(orgId, body.reason(), body.note()));
    }

    @Operation(summary = "Reinstate: lift the suspension hold (compliance payout holds stay) and the buy-box "
            + "restriction; audited (sellers:enforce)")
    @PostMapping("/v1/seller-enforcement/{orgId}/reinstate")
    StandingResponse reinstate(@PathVariable String orgId, @Valid @RequestBody ReinstateRequest body) {
        return StandingResponse.of(enforcement.reinstate(orgId, body.note()));
    }
}
