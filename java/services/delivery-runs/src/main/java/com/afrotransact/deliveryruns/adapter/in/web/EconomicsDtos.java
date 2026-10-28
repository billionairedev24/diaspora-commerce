package com.afrotransact.deliveryruns.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.deliveryruns.application.EconomicsSettingsCommand;
import com.afrotransact.deliveryruns.application.WindowPlanCommand;
import com.afrotransact.deliveryruns.domain.AreaKey;
import com.afrotransact.deliveryruns.domain.EconomicsChange;
import com.afrotransact.deliveryruns.domain.EconomicsRevision;
import com.afrotransact.deliveryruns.domain.EconomicsSettings;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Wire shapes of the staff-editable driver cost model and the window break-even plan (S265). */
final class EconomicsDtos {

    private EconomicsDtos() {
    }

    @Schema(name = "EconomicsSettings", description = """
            The driver cost model behind run economics: cost(n) = fixed (drive + sweep) + n x per-order time at the
            driver cost per hour, plus the miles at the vehicle cost per mile. The ETag carries the version for
            If-Match; version 0 means nobody has saved yet and the environment's defaults apply.""")
    record SettingsResponse(@Schema(description = "Minor units") long driverCostPerHourMinor,
                            @Schema(description = "Minor units") long driverCostPerMileMinor,
                            double collectionMinutes, double perStopMinutes, double pickupPointStopMinutes,
                            double handoverMinutesPerOrder, boolean roundTrip, long version,
                            @Schema(description = "CONFIG while the environment's defaults apply, STAFF once saved")
                            String source, Instant updatedAt, String updatedBy, String reason) {

        static SettingsResponse of(EconomicsRevision r) {
            EconomicsSettings s = r.settings();
            return new SettingsResponse(s.driverCostPerHourMinor(), s.driverCostPerMileMinor(), s.collectionMinutes(),
                    s.perStopMinutes(), s.pickupPointStopMinutes(), s.handoverMinutesPerOrder(), s.roundTrip(),
                    r.version(), r.saved() ? "STAFF" : "CONFIG", r.updatedAt(), r.updatedBy(), r.reason());
        }
    }

    @Schema(name = "EconomicsSettingsRequest", description = """
            The new numbers (money in minor units, times in minutes). The reason is required and is logged with who
            and when.""")
    record SettingsRequest(@NotNull @PositiveOrZero @Max(EconomicsSettings.MAX_PER_HOUR_MINOR) Long driverCostPerHourMinor,
                           @NotNull @PositiveOrZero @Max(EconomicsSettings.MAX_PER_MILE_MINOR) Long driverCostPerMileMinor,
                           @NotNull @PositiveOrZero Double collectionMinutes,
                           @NotNull @PositiveOrZero Double perStopMinutes,
                           @NotNull @PositiveOrZero Double pickupPointStopMinutes,
                           @NotNull @PositiveOrZero Double handoverMinutesPerOrder, @NotNull Boolean roundTrip,
                           @NotBlank @Size(max = 500) String reason) {

        EconomicsSettingsCommand toCommand() {
            return new EconomicsSettingsCommand(driverCostPerHourMinor, driverCostPerMileMinor, collectionMinutes,
                    perStopMinutes, pickupPointStopMinutes, handoverMinutesPerOrder, roundTrip, reason);
        }
    }

    @Schema(name = "EconomicsSettingsNumbers", description = "The cost model of one side of a change")
    record Numbers(long driverCostPerHourMinor, long driverCostPerMileMinor, double collectionMinutes,
                   double perStopMinutes, double pickupPointStopMinutes, double handoverMinutesPerOrder,
                   boolean roundTrip) {

        static Numbers of(EconomicsSettings s) {
            return new Numbers(s.driverCostPerHourMinor(), s.driverCostPerMileMinor(), s.collectionMinutes(),
                    s.perStopMinutes(), s.pickupPointStopMinutes(), s.handoverMinutesPerOrder(), s.roundTrip());
        }
    }

    @Schema(name = "EconomicsSettingsChange", description = "One entry of the audit trail: the numbers before (the "
            + "environment defaults for the first save) and after, who, when and why")
    record ChangeResponse(UUID id, long version, Numbers before, Numbers after, String actor, String reason,
                          Instant at) {

        static ChangeResponse of(EconomicsChange c) {
            return new ChangeResponse(c.id(), c.version(), Numbers.of(c.before()), Numbers.of(c.after()), c.actor(),
                    c.reason(), c.at());
        }
    }

    @Schema(name = "EconomicsSettingsChanges")
    record ChangesResponse(List<ChangeResponse> items) {

        static ChangesResponse of(List<EconomicsChange> changes) {
            return new ChangesResponse(changes.stream().map(ChangeResponse::of).toList());
        }
    }

    @Schema(name = "WindowPlanRequest", description = """
            What the window wizard knows. The location is the one named, else the active one matching the first
            city target. Nulls: the fee rules decide the fees; the expected orders default to the minimum.""")
    record PlanRequest(@Schema(description = "Location of the closing run (null = matched by city)") UUID locationId,
                       @Size(max = 50) @Schema(description = "country/region or country/region/city",
                               example = "[\"US/TX/Bastrop\"]") List<String> targets,
                       Boolean door, Boolean pickup, @PositiveOrZero @Max(10_000) Long doorFeeMinor,
                       @PositiveOrZero @Max(10_000) Long pickupFeeMinor,
                       @Min(0) @Max(100_000) Integer minOrders, @Min(0) @Max(100_000) Integer expectedOrders) {

        WindowPlanCommand toCommand() {
            List<AreaKey> keys = targets == null ? List.of() : targets.stream().map(AreaKey::parse).toList();
            return new WindowPlanCommand(locationId, keys, door == null || door, pickup != null && pickup,
                    doorFeeMinor, pickupFeeMinor, minOrders, expectedOrders);
        }
    }
}
