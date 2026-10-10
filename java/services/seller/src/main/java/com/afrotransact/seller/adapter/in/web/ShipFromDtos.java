package com.afrotransact.seller.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.seller.application.ShipFromAddressInput;
import com.afrotransact.seller.domain.ShipFromAddress;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
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

    @Schema(name = "ShipFromAddress")
    record ShipFromAddressResponse(UUID id, String orgId, String label, String name, String company, String phone,
                                   String line1, String line2, String city, String state, String postalCode,
                                   String country, boolean isDefault, Instant createdAt, Instant updatedAt,
                                   long version) {

        static ShipFromAddressResponse of(ShipFromAddress a) {
            return new ShipFromAddressResponse(a.id(), a.orgId(), a.label(), a.name(), a.company(), a.phone(),
                    a.line1(), a.line2(), a.city(), a.state(), a.postalCode(), a.country(), a.isDefault(),
                    a.createdAt(), a.updatedAt(), a.version());
        }

        static List<ShipFromAddressResponse> all(List<ShipFromAddress> list) {
            return list.stream().map(ShipFromAddressResponse::of).toList();
        }
    }
}
