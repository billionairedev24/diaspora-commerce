package com.afrotransact.deliveryruns.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What orders and fulfilment ask delivery-runs over HTTP today, entered in-process under the calling module's principal:
 * {@code GET /v1/public/runs/available} (public), {@code POST/DELETE /v1/service/runs/{id}/reservations},
 * {@code GET /v1/service/preorders/eligibility} and {@code POST /v1/service/delivery-fees/carrier} (scope
 * {@value #RESERVE}), and {@code GET /v1/service/runs/{id}/dispatch} (scope {@value #READ}). A caller without the scope is refused with
 * {@code ModuleApiException} 403; the other refusals keep the endpoint's status and code ({@code delivery-runs.run-full} /
 * {@code run-closed} 409, 404 for an unknown run, 502 {@code delivery-runs.geo-unavailable}).
 */
public interface DeliveryRunsApi {

    String RESERVE = "delivery-runs:reserve";
    String READ = "delivery-runs:read";

    /** Open runs covering a 5-digit ZIP, earliest cutoff first; full ones included and flagged. No scope (a public read). */
    List<OpenRun> openRuns(String zip, int earlyAccessHours);

    /** Takes the order's slot on exactly this run (never rolled to the next); idempotent per order. */
    void reserve(String runId, String orderId, String customerId, int earlyAccessHours, RunCharge charge);

    /** Gives the order's slot back; 404 when it holds none. */
    void release(String runId, String orderId);

    /** One entry per requested ATIN. */
    List<Eligibility> eligibility(String country, String region, String city, List<String> atins, int earlyAccessHours);

    /** The carrier fee rule for an order shipped beyond coverage; empty when no rule applies. */
    Optional<FeeRule> carrierFee(String currency, Long carrierQuoteMinor, long itemsMinor);

    /** The confirmed run fulfilment plans: where it leaves from and its confirmed orders. */
    RunPlan dispatch(String runId);

    /** What an order of a run is charged (all null when not reported); {@code kind} is DOOR, PICKUP or CARRIER. */
    record RunCharge(Long feeMinor, Long totalMinor, String kind) {
    }

    /** The fee rule behind an amount (ADR 0005 section 3). */
    record FeeRule(String method, String ruleId, long ruleVersion, String currency, long amountMinor, String source, String band,
                   BigDecimal distanceMiles) {
    }

    record OpenRun(String runId, String town, Instant cutoffAt, LocalDate deliversOn, String timeZone, long feeMinor, String currency,
                   String dropMode, FeeRule feeRule, int minOrders, int ordersSoFar, boolean full) {
    }

    /** Null window fields mean "no window covers the place". Fees are null when not offered. */
    record Eligibility(String atin, boolean preorder, String windowId, String windowCode, String windowName, String stage,
                       LocalDate deliversOn, Instant closesAt, Boolean door, Boolean pickup, Long doorFeeMinor, Long pickupFeeMinor,
                       String currency, Set<String> allowedMethods, String pickupPointId, FeeRule doorFeeRule,
                       FeeRule pickupFeeRule) {
    }

    record PickupPoint(String name, String line1, String city, String state, String postalCode, String hours, String notes) {
    }

    /** Coordinates are null when the location has no origin. */
    record RunPlan(String runId, String status, String town, String state, String timeZone, Instant windowStart, Instant windowEnd,
                   Instant sellerPickupAt, boolean doorstep, PickupPoint pickupPoint, Double originLat, Double originLng,
                   List<String> confirmedOrders) {

        public RunPlan {
            confirmedOrders = confirmedOrders == null ? List.of() : List.copyOf(confirmedOrders);
        }
    }
}
