package com.afrotransact.trustops.adapter.in.web;

import java.time.Instant;
import java.util.List;

import com.afrotransact.trustops.application.SellerHealthView;
import com.afrotransact.trustops.domain.EnforcementStatus;
import com.afrotransact.trustops.domain.SellerEnforcement;
import com.afrotransact.trustops.domain.SellerHealthMetrics;
import com.afrotransact.trustops.domain.SellerHealthReport;
import com.afrotransact.trustops.domain.SellerHealthStatus;
import com.afrotransact.trustops.domain.SuspensionReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Seller health and enforcement DTOs. */
final class SellerDtos {

    private SellerDtos() {
    }

    @Schema(description = "Seller health over the rolling window (rates null when unknown)")
    record HealthResponse(String orgId, SellerHealthStatus status, List<String> breaches, Instant windowStart,
                          Instant windowEnd, int shipments, int delivered, int lateDeliveries,
                          int sellerCancellations, int claims, Double lateShipmentRate, Double onTimeRate,
                          Double cancellationRate, Double defectRate, Instant computedAt) {

        static HealthResponse of(SellerHealthReport r) {
            SellerHealthMetrics m = r.metrics();
            return new HealthResponse(m.orgId(), r.status(), r.breaches(), m.windowStart(), m.windowEnd(),
                    m.shipments(), m.delivered(), m.lateDeliveries(), m.sellerCancellations(), m.claims(),
                    m.lateShipmentRate(), m.onTimeRate(), m.cancellationRate(), m.defectRate(), r.computedAt());
        }
    }

    @Schema(description = "Seller standing and which upstream effects are applied")
    record StandingResponse(String orgId, EnforcementStatus status, SuspensionReason reason, String note,
                            String actorId, Instant changedAt, boolean payoutsHeld, boolean buyBoxRestricted,
                            boolean stepsPending) {

        static StandingResponse of(SellerEnforcement e) {
            return new StandingResponse(e.orgId(), e.status(), e.reason(), e.note(), e.actorId(), e.changedAt(),
                    e.payoutsHeld(), e.buyBoxRestricted(), e.payoutStepPending() || e.buyBoxStepPending());
        }
    }

    @Schema(description = "Health, last push to offers and standing")
    record SellerHealthResponse(HealthResponse health, boolean lastPushedToOffers, StandingResponse standing) {

        static SellerHealthResponse of(SellerHealthView v) {
            return new SellerHealthResponse(HealthResponse.of(v.current()), v.lastPushedToOffers(),
                    StandingResponse.of(v.standing()));
        }
    }

    @Schema(description = "Suspend a seller: holds payouts and restricts it in the buy box")
    record SuspendRequest(@NotNull SuspensionReason reason, @NotBlank @Size(max = 1000) String note) {
    }

    @Schema(description = "Reinstate a seller: releases the payout hold and the buy-box restriction")
    record ReinstateRequest(@NotBlank @Size(max = 1000) String note) {
    }
}
