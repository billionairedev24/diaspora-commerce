package com.afrotransact.deliveryruns.adapter.in.web;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.deliveryruns.application.LocationCommand;
import com.afrotransact.deliveryruns.application.LocationHistory;
import com.afrotransact.deliveryruns.domain.AreaKind;
import com.afrotransact.deliveryruns.domain.Channel;
import com.afrotransact.deliveryruns.domain.CutoffPolicy;
import com.afrotransact.deliveryruns.domain.DropMode;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.LocationDetails;
import com.afrotransact.deliveryruns.domain.PickupPoint;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunArea;
import com.afrotransact.deliveryruns.port.SubscriberCounts;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Request and response bodies for locations. */
final class LocationDtos {

    private LocationDtos() {
    }

    @Schema(name = "PickupPoint", description = "Community pickup point of a PICKUP_POINT drop")
    record PickupPointBody(@NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 200) String line1,
                           @NotBlank @Size(max = 80) String city, @Size(max = 8) String state,
                           @Size(max = 10) String postalCode,
                           @NotBlank @Size(max = 120) @Schema(example = "Sat 2–5 PM") String hours,
                           @Size(max = 500) String notes) {

        PickupPoint toDomain() {
            return new PickupPoint(name, line1, city, state, postalCode, hours, notes);
        }

        static PickupPointBody of(PickupPoint p) {
            return p == null ? null : new PickupPointBody(p.name(), p.line1(), p.city(), p.state(), p.postalCode(),
                    p.hours(), p.notes());
        }
    }

    @Schema(name = "LocationRequest", description = """
            A town or area. areaKind RADIUS needs radiusMiles; POLYGON needs geoZoneId (an existing geo-service zone)
            or polygonGeoJson (registered in geo-service as a zone owned by delivery-runs:<slug>). centerLat/centerLng
            is always required: the circle centre, and the drive destination for run economics.""")
    record LocationRequest(
            @NotBlank @Size(max = 80) @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$")
            @Schema(example = "bastrop-tx") String slug,
            @NotBlank @Size(max = 120) @Schema(example = "Bastrop") String name,
            @NotBlank @Size(max = 16) @Schema(example = "US") String market,
            @Size(max = 8) @Schema(example = "TX") String state,
            @Schema(example = "America/Chicago", description = "IANA time zone; default America/Chicago") String timeZone,
            @NotNull AreaKind areaKind,
            @Size(max = 80) String geoZoneId,
            @Size(max = 200_000) String polygonGeoJson,
            @NotNull @DecimalMin("-90") @DecimalMax("90") @Schema(example = "30.1105") Double centerLat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") @Schema(example = "-97.3153") Double centerLng,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("100") @Schema(example = "12") Double radiusMiles,
            @DecimalMin("-90") @DecimalMax("90") Double originLat,
            @DecimalMin("-180") @DecimalMax("180") Double originLng,
            @PositiveOrZero @Schema(example = "799", description = "Default run fee, minor units (default 799)")
            Long defaultFeeMinor,
            @Pattern(regexp = "^[A-Z]{3}$") @Schema(example = "USD") String currency,
            @Min(1) @Max(10_000) @Schema(example = "10") Integer defaultMinOrders,
            @Min(1) @Max(10_000) Integer defaultCapacityOrders,
            @Min(1) @Max(100_000) Integer defaultCapacityPackages,
            DropMode dropMode,
            @Valid PickupPointBody pickupPoint,
            @Size(max = 500) @Schema(example = "https://chat.whatsapp.com/AbCdEf123") String whatsappInviteUrl,
            @Size(max = 10) Map<String, String> socialLinks,
            CutoffPolicy defaultCutoffPolicy,
            @Min(1) @Max(30) Integer defaultExtendDays,
            Boolean active) {

        LocationCommand toCommand() {
            return new LocationCommand(slug, name, market, state, timeZone, areaKind, geoZoneId, polygonGeoJson,
                    centerLat, centerLng, radiusMiles, originLat, originLng, defaultFeeMinor, currency,
                    defaultMinOrders, defaultCapacityOrders, defaultCapacityPackages, dropMode,
                    pickupPoint == null ? null : pickupPoint.toDomain(), whatsappInviteUrl, socialLinks,
                    defaultCutoffPolicy, defaultExtendDays, active);
        }
    }

    @Schema(name = "RunArea")
    record AreaResponse(AreaKind kind, String geoZoneId, double centerLat, double centerLng, Double radiusMiles) {

        static AreaResponse of(RunArea a) {
            return new AreaResponse(a.kind(), a.geoZoneId(), a.center().lat(), a.center().lng(), a.radiusMiles());
        }
    }

    @Schema(name = "Location")
    record LocationResponse(UUID id, String slug, String name, String market, String state, String timeZone,
                            AreaResponse area, Double originLat, Double originLng, long defaultFeeMinor,
                            String currency, int defaultMinOrders, Integer defaultCapacityOrders,
                            Integer defaultCapacityPackages, DropMode dropMode, PickupPointBody pickupPoint,
                            String whatsappInviteUrl, Map<String, String> socialLinks,
                            CutoffPolicy defaultCutoffPolicy, int defaultExtendDays, boolean active,
                            Instant createdAt, Instant updatedAt, long version) {

        static LocationResponse of(Location l) {
            LocationDetails d = l.details();
            return new LocationResponse(l.id(), d.slug(), d.name(), d.market(), d.state(), d.timeZone().getId(),
                    AreaResponse.of(d.area()), d.origin() == null ? null : d.origin().lat(),
                    d.origin() == null ? null : d.origin().lng(), d.defaultFeeMinor(), d.currency(),
                    d.defaultMinOrders(), d.defaultCapacity().maxOrders(), d.defaultCapacity().maxPackages(),
                    d.dropMode(), PickupPointBody.of(d.pickupPoint()), d.whatsappInviteUrl(), d.socialLinks(),
                    d.defaultCutoffPolicy(), d.defaultExtendDays(), d.active(), l.createdAt(), l.updatedAt(),
                    l.version());
        }
    }

    @Schema(name = "SubscriberCounts")
    record SubscriberCountsResponse(UUID locationId, long total, long active, long pending,
                                    Map<Channel, Long> byChannel) {

        static SubscriberCountsResponse of(SubscriberCounts c) {
            return new SubscriberCountsResponse(c.locationId(), c.total(), c.active(), c.pending(), c.byChannel());
        }
    }

    @Schema(name = "LocationRunSummary")
    record RunSummary(UUID id, String status, Instant windowStart, Instant cutoffAt, int orders, int packages,
                      long feeMinor, long feeRevenueMinor, int minOrders, boolean minimumMet) {

        static RunSummary of(Run r) {
            return new RunSummary(r.id(), r.status().name(), r.terms().window().start(), r.terms().cutoffAt(),
                    r.reservedOrders(), r.reservedPackages(), r.terms().feeMinor(),
                    r.reservedOrders() * r.terms().feeMinor(), r.terms().minOrders(), r.minimumMet());
        }
    }

    @Schema(name = "MonthlyCount")
    record MonthlyCountResponse(@Schema(example = "2026-10") String month, long count) {

        static MonthlyCountResponse of(YearMonth month, long count) {
            return new MonthlyCountResponse(month.toString(), count);
        }
    }

    @Schema(name = "LocationHistory", description = "Per-location history: runs with orders, subscriber growth")
    record LocationHistoryResponse(LocationResponse location, List<RunSummary> runs,
                                   SubscriberCountsResponse subscribers, List<MonthlyCountResponse> subscriberGrowth) {

        static LocationHistoryResponse of(LocationHistory h) {
            return new LocationHistoryResponse(LocationResponse.of(h.location()),
                    h.runs().stream().map(RunSummary::of).toList(), SubscriberCountsResponse.of(h.subscribers()),
                    h.subscriberGrowth().stream().map(m -> MonthlyCountResponse.of(m.month(), m.count())).toList());
        }
    }
}
