package com.afrotransact.seller.adapter.in.web;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import com.afrotransact.seller.application.PickupInput;
import com.afrotransact.seller.application.ShipFromAddressInput;
import com.afrotransact.seller.domain.AfrotransactPickup;
import com.afrotransact.seller.domain.GeoPosition;
import com.afrotransact.seller.domain.PickupWindow;
import com.afrotransact.seller.domain.ShipFromAddress;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request / response bodies of the ship-from address API. */
final class ShipFromDtos {

    private ShipFromDtos() {
    }

    @Schema(name = "ShipFromAddressRequest", description = "A ship-from address; US addresses need a state code and ZIP")
    record ShipFromAddressRequest(@Size(max = 60) @Schema(example = "Houston warehouse") String label,
                                  @NotBlank @Size(max = 120) @Schema(example = "Ade Okafor") String name,
                                  @Size(max = 120) String company,
                                  @Size(max = 40) @Schema(example = "+1 713 555 0100") String phone,
                                  @NotBlank @Size(max = 120) @Schema(example = "4521 Main St") String line1,
                                  @Size(max = 120) String line2,
                                  @NotBlank @Size(max = 80) @Schema(example = "Houston") String city,
                                  @NotBlank @Size(max = 40) @Schema(example = "TX") String state,
                                  @NotBlank @Size(max = 12) @Schema(example = "77002") String postalCode,
                                  @Size(max = 2) @Schema(example = "US", defaultValue = "US") String country,
                                  @Schema(description = "Also make it the default") Boolean makeDefault,
                                  @Schema(description = "Version being edited (updates); 409 when it changed")
                                  Long version) {

        ShipFromAddressInput toInput() {
            return new ShipFromAddressInput(label, name, company, phone, line1, line2, city, state, postalCode,
                    country, Boolean.TRUE.equals(makeDefault), version);
        }
    }

    static final String HH_MM = "^([01][0-9]|2[0-3]):[0-5][0-9]$";

    @Schema(name = "PickupWindow", description = "One pickup window: day of the week and 24-hour local times "
            + "(HH:MM, opens before closes)")
    record PickupWindowDto(@NotNull DayOfWeek day,
                           @NotBlank @Pattern(regexp = HH_MM) @Schema(description = "Opening time, HH:MM") String opens,
                           @NotBlank @Pattern(regexp = HH_MM) @Schema(description = "Closing time, HH:MM")
                           String closes) {

        PickupWindow toWindow() {
            return new PickupWindow(day, LocalTime.parse(opens), LocalTime.parse(closes));
        }

        static PickupWindowDto of(PickupWindow w) {
            return new PickupWindowDto(w.day(), w.opens().toString(), w.closes().toString());
        }
    }

    @Schema(name = "AfrotransactPickupRequest", description = "Turn AfroTransact Delivery pickup on or off for a "
            + "ship-from location (S255)")
    record PickupRequest(@NotNull Boolean enabled,
                         @Size(max = 14) @Schema(description = "Pickup windows; at least one to enable")
                         List<@Valid PickupWindowDto> hours,
                         @Schema(description = "Version being edited; 409 when it changed") Long version) {

        PickupInput toInput() {
            return new PickupInput(enabled, hours == null ? List.of()
                    : hours.stream().map(PickupWindowDto::toWindow).toList(), version);
        }
    }

    @Schema(name = "GeoPosition", description = "Where the address is (geo-service geocoding)")
    record PositionDto(double lat, double lng,
                       @Schema(allowableValues = {"ADDRESS", "ZIP", "POINT"}) String precision,
                       Instant geocodedAt) {

        static PositionDto of(GeoPosition p) {
            return p == null ? null : new PositionDto(p.lat(), p.lng(), p.precision(), p.geocodedAt());
        }
    }

    @Schema(name = "AfrotransactPickup", description = "AfroTransact Delivery from this location: the seller packs, "
            + "the nearest AfroTransact driver collects during the pickup hours")
    record PickupDto(boolean enabled, List<PickupWindowDto> hours,
                     @Schema(description = "Delivery zone covering (or nearest to) the location") String zoneName,
                     Instant enabledAt) {

        static PickupDto of(AfrotransactPickup p) {
            return new PickupDto(p.enabled(), p.hours().stream().map(PickupWindowDto::of).toList(), p.zoneName(),
                    p.enabledAt());
        }
    }

    @Schema(name = "ShipFromAddress")
    record ShipFromAddressResponse(UUID id, String orgId, String label, String name, String company, String phone,
                                   String line1, String line2, String city, String state, String postalCode,
                                   String country, boolean isDefault, Instant createdAt, Instant updatedAt,
                                   long version,
                                   @Schema(description = "Geocoded position; null until geo-service located it")
                                   PositionDto position,
                                   PickupDto afrotransactPickup) {

        static ShipFromAddressResponse of(ShipFromAddress a) {
            return new ShipFromAddressResponse(a.id(), a.orgId(), a.label(), a.name(), a.company(), a.phone(),
                    a.line1(), a.line2(), a.city(), a.state(), a.postalCode(), a.country(), a.isDefault(),
                    a.createdAt(), a.updatedAt(), a.version(), PositionDto.of(a.position()), PickupDto.of(a.pickup()));
        }

        static List<ShipFromAddressResponse> all(List<ShipFromAddress> list) {
            return list.stream().map(ShipFromAddressResponse::of).toList();
        }
    }
}
