package com.afrotransact.deliveryruns.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.deliveryruns.adapter.in.web.LocationDtos.PickupPointBody;
import com.afrotransact.deliveryruns.application.DispatchList;
import com.afrotransact.deliveryruns.application.EconomicsPreviewCommand;
import com.afrotransact.deliveryruns.application.ExtendCommand;
import com.afrotransact.deliveryruns.application.RunCommand;
import com.afrotransact.deliveryruns.application.SlotReservation;
import com.afrotransact.deliveryruns.domain.CutoffPolicy;
import com.afrotransact.deliveryruns.domain.DropMode;
import com.afrotransact.deliveryruns.domain.GeoPoint;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.RouteEstimate;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunEconomics;
import com.afrotransact.deliveryruns.domain.RunMilestones;
import com.afrotransact.deliveryruns.domain.RunOrder;
import com.afrotransact.deliveryruns.domain.RunTerms;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Request and response bodies for runs, run slots and dispatch. */
final class RunDtos {

    private RunDtos() {
    }

    @Schema(name = "RunRequest", description = """
            Schedule (or reschedule) a run. Null fee, currency, minimum, capacity, drop mode, pickup point, cutoff
            policy and extend days take the location's defaults. OPEN runs keep their fee, currency, cutoff and drop
            mode (use extend to move the cutoff); capacity can never drop below what is reserved.""")
    record RunRequest(@Schema(description = "Required on create; a run cannot move to another location") UUID locationId,
                      @NotNull Instant windowStart, @NotNull Instant windowEnd, @NotNull Instant cutoffAt,
                      @Schema(description = "ANNOUNCED runs open automatically at this time") Instant opensAt,
                      @Schema(description = "When the sweep collects from sellers") Instant sellerPickupAt,
                      @PositiveOrZero @Schema(example = "799") Long feeMinor,
                      @Pattern(regexp = "^[A-Z]{3}$") String currency,
                      @Min(1) @Max(10_000) Integer minOrders,
                      @Min(1) @Max(10_000) Integer capacityOrders,
                      @Min(1) @Max(100_000) Integer capacityPackages,
                      DropMode dropMode, @Valid PickupPointBody pickupPoint, CutoffPolicy cutoffPolicy,
                      @Min(1) @Max(30) Integer extendDays) {

        RunCommand toCommand() {
            return new RunCommand(locationId, windowStart, windowEnd, cutoffAt, opensAt, sellerPickupAt, feeMinor,
                    currency, minOrders, capacityOrders, capacityPackages, dropMode,
                    pickupPoint == null ? null : pickupPoint.toDomain(), cutoffPolicy, extendDays);
        }
    }

    @Schema(name = "ExtendRunRequest", description = "New cutoff; without a new window the window moves by as much")
    record ExtendRequest(@NotNull Instant cutoffAt, Instant windowStart, Instant windowEnd, Instant sellerPickupAt,
                         @Size(max = 500) String reason) {

        ExtendCommand toCommand() {
            return new ExtendCommand(cutoffAt, windowStart, windowEnd, sellerPickupAt, reason);
        }
    }

    @Schema(name = "CancelRunRequest")
    record CancelRequest(@NotBlank @Size(max = 500) @Schema(example = "Weather: tropical storm warning") String reason) {
    }

    @Schema(name = "EconomicsPreviewRequest", description = "Nulls take the location's defaults")
    record EconomicsPreviewRequest(@NotNull UUID locationId, @PositiveOrZero Long feeMinor,
                                   @Min(1) @Max(10_000) Integer minOrders,
                                   @Min(1) @Max(10_000) Integer capacityOrders, DropMode dropMode,
                                   @Min(0) @Max(10_000) Integer expectedOrders,
                                   @PositiveOrZero Long commissionShareMinor) {

        EconomicsPreviewCommand toCommand() {
            return new EconomicsPreviewCommand(locationId, feeMinor, minOrders, capacityOrders, dropMode,
                    expectedOrders, commissionShareMinor);
        }
    }

    @Schema(name = "RouteEstimate")
    record RouteResponse(double roadMiles, double driveMinutes, String method) {

        static RouteResponse of(RouteEstimate e) {
            return e == null ? null : new RouteResponse(e.roadMiles(), e.driveMinutes(), e.method());
        }
    }

    @Schema(name = "RunMilestones")
    record MilestonesResponse(Instant announcedAt, Instant openedAt, Instant cutOffAt, Instant confirmedAt,
                              Instant collectingAt, Instant outForDeliveryAt, Instant completedAt,
                              Instant cancelledAt) {

        static MilestonesResponse of(RunMilestones m) {
            return new MilestonesResponse(m.announcedAt(), m.openedAt(), m.cutOffAt(), m.confirmedAt(),
                    m.collectingAt(), m.outForDeliveryAt(), m.completedAt(), m.cancelledAt());
        }
    }

    @Schema(name = "Run")
    record RunResponse(UUID id, UUID locationId, String status, Instant windowStart, Instant windowEnd,
                       Instant cutoffAt, Instant originalCutoffAt, Instant opensAt, Instant sellerPickupAt,
                       long feeMinor, String currency, int minOrders, Integer capacityOrders,
                       Integer capacityPackages, DropMode dropMode, PickupPointBody pickupPoint,
                       CutoffPolicy cutoffPolicy, int extendDays, int extensions, int reservedOrders,
                       int reservedPackages, Integer ordersLeft, boolean minimumMet, String cancelReason,
                       RouteResponse estimate,
                       @Schema(description = "Fewest orders covering the estimated cost (create / get only)")
                       Integer breakEvenOrders,
                       MilestonesResponse milestones, Instant createdAt, Instant updatedAt, long version) {

        static RunResponse of(Run r) {
            return of(r, null);
        }

        static RunResponse of(Run r, RunEconomics economics) {
            RunTerms t = r.terms();
            return new RunResponse(r.id(), r.locationId(), r.status().name(), t.window().start(), t.window().end(),
                    t.cutoffAt(), r.originalCutoffAt(), t.opensAt(), t.sellerPickupAt(), t.feeMinor(), t.currency(),
                    t.minOrders(), t.capacity().maxOrders(), t.capacity().maxPackages(), t.dropMode(),
                    PickupPointBody.of(t.pickupPoint()), t.cutoffPolicy(), t.extendDays(), r.extensions(),
                    r.reservedOrders(), r.reservedPackages(), t.capacity().ordersLeft(r.reservedOrders()),
                    r.minimumMet(), r.cancelReason(), RouteResponse.of(r.estimate()),
                    economics == null ? null : economics.breakEvenOrders(), MilestonesResponse.of(r.milestones()),
                    r.createdAt(), r.updatedAt(), r.version());
        }
    }

    @Schema(name = "RunOrder", description = "A run slot held by an order")
    record RunOrderResponse(UUID id, UUID runId, String orderId, String customerId, int packages, String status,
                            String handoverCode, UUID requestedRunId, Instant reservedAt, Instant updatedAt,
                            @Schema(description = "Where the order's package is on the run: PENDING, COLLECTED, "
                                    + "OUT_FOR_DELIVERY, FAILED, DELIVERED or RETURNED (from orders-service shipment "
                                    + "events)") String stage,
                            Instant stageAt,
                            @Schema(description = "When the package was handed over; the seller payout hold starts "
                                    + "from it") Instant deliveredAt) {

        static RunOrderResponse of(RunOrder o) {
            return new RunOrderResponse(o.id(), o.runId(), o.orderId(), o.customerId(), o.packages(),
                    o.status().name(), o.handoverCode(), o.requestedRunId(), o.reservedAt(), o.updatedAt(),
                    o.stage().name(), o.stageAt(), o.deliveredAt());
        }
    }

    @Schema(name = "ReserveSlotRequest")
    record ReserveRequest(@NotBlank @Size(max = 80) String orderId, @NotBlank @Size(max = 120) String customerId,
                          @Min(1) @Max(50) @Schema(description = "Default 1") Integer packages,
                          @Schema(description = "Roll over to the location's next open run when this one is full or "
                                  + "closed (default true)") Boolean allowNextRun) {
    }

    @Schema(name = "SlotReservation")
    record ReservationResponse(RunOrderResponse slot, UUID runId, UUID requestedRunId, boolean moved,
                               Instant windowStart, Instant windowEnd, Instant cutoffAt, long feeMinor,
                               String currency) {

        static ReservationResponse of(SlotReservation r) {
            RunTerms t = r.run().terms();
            return new ReservationResponse(RunOrderResponse.of(r.slot()), r.run().id(), r.requestedRunId(), r.moved(),
                    t.window().start(), t.window().end(), t.cutoffAt(), t.feeMinor(), t.currency());
        }
    }

    @Schema(name = "DispatchLocation")
    record DispatchLocation(UUID id, String slug, String name, String state, String timeZone, double centerLat,
                            double centerLng,
                            @Schema(description = "Where the run leaves from (the hub); drops are ordered by road "
                                    + "distance from it") double originLat, double originLng) {

        static DispatchLocation of(Location l, GeoPoint origin) {
            return new DispatchLocation(l.id(), l.slug(), l.name(), l.details().state(),
                    l.details().timeZone().getId(), l.details().area().center().lat(),
                    l.details().area().center().lng(), origin.lat(), origin.lng());
        }
    }

    @Schema(name = "DispatchList", description = "Confirmed run orders for one sweep (seller pickups, then drops)")
    record DispatchResponse(RunResponse run, DispatchLocation location, DropMode dropMode,
                            PickupPointBody pickupPoint, Instant sellerPickupAt, List<RunOrderResponse> orders) {

        static DispatchResponse of(DispatchList d) {
            RunTerms t = d.run().terms();
            return new DispatchResponse(RunResponse.of(d.run()), DispatchLocation.of(d.location(), d.origin()), t.dropMode(),
                    PickupPointBody.of(t.pickupPoint()), t.sellerPickupAt(),
                    d.orders().stream().map(RunOrderResponse::of).toList());
        }
    }
}
