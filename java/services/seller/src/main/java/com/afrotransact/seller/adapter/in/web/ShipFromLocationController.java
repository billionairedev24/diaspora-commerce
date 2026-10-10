package com.afrotransact.seller.adapter.in.web;

import java.util.UUID;

import com.afrotransact.seller.adapter.in.web.ShipFromDtos.ShipFromAddressResponse;
import com.afrotransact.seller.application.ShipFromAddressService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Ship-from addresses")
@RestController
class ShipFromLocationController {

    private final ShipFromAddressService addresses;

    ShipFromLocationController(ShipFromAddressService addresses) {
        this.addresses = addresses;
    }

    @Operation(summary = "One ship-from location by id (services: sellers:ship-from.read)",
            description = "For offers-service (an AFROTRANSACT_DELIVERY offer must reference a location with pickup "
                    + "enabled), orders-service (checkout distance from the location's position) and fulfilment "
                    + "(pickup stop address). Staff with seller:ship-from and the organisation's members can read it "
                    + "too.")
    @GetMapping("/v1/ship-from-locations/{id}")
    ShipFromAddressResponse shipFromLocation(@PathVariable UUID id) {
        return ShipFromAddressResponse.of(addresses.location(id));
    }
}
