package com.afrotransact.seller.adapter.in.web;

import java.util.List;
import java.util.UUID;

import com.afrotransact.seller.adapter.in.web.ShipFromDtos.PickupRequest;
import com.afrotransact.seller.adapter.in.web.ShipFromDtos.ShipFromAddressRequest;
import com.afrotransact.seller.adapter.in.web.ShipFromDtos.ShipFromAddressResponse;
import com.afrotransact.seller.application.ShipFromAddressService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Ship-from addresses", description = "Where a seller ships its own orders from (label purchase, rates, "
        + "AfroTransact Delivery pickup). Members read; owner, admin and fulfilment members change them. Addresses are "
        + "geocoded with geo-service when saved (position).")
@RestController
@RequestMapping("/v1/orgs/{orgId}/ship-from-addresses")
class ShipFromAddressController {

    private final ShipFromAddressService addresses;

    ShipFromAddressController(ShipFromAddressService addresses) {
        this.addresses = addresses;
    }

    @Operation(summary = "Ship-from addresses, default first")
    @GetMapping
    List<ShipFromAddressResponse> listShipFrom(@PathVariable String orgId) {
        return ShipFromAddressResponse.all(addresses.list(orgId));
    }

    @Operation(summary = "Add a ship-from address (the first one becomes the default)",
            description = "422 seller.invalid-ship-from with fieldErrors; at most 20 per organisation.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ShipFromAddressResponse createShipFrom(@PathVariable String orgId,
                                           @Valid @RequestBody ShipFromAddressRequest body) {
        return ShipFromAddressResponse.of(addresses.create(orgId, body.toInput()));
    }

    @Operation(summary = "Change a ship-from address", description = "Optimistic: send the version you edited; "
            + "409 seller.concurrent-update when it changed meanwhile.")
    @PutMapping("/{id}")
    ShipFromAddressResponse updateShipFrom(@PathVariable String orgId, @PathVariable UUID id,
                                           @Valid @RequestBody ShipFromAddressRequest body) {
        return ShipFromAddressResponse.of(addresses.update(orgId, id, body.toInput()));
    }

    @Operation(summary = "Turn AfroTransact Delivery pickup on or off for a location (S255)",
            description = "Enabling needs an approved organisation (409 seller.org-not-approved), at least one pickup "
                    + "window (422 seller.invalid-pickup-hours), a street-level geocoded position (422 "
                    + "seller.ship-from-not-geocoded) and a location inside an active AfroTransact DELIVERY zone or "
                    + "within PICKUP_MAX_RADIUS_MILES of one (422 seller.pickup-outside-delivery-area); 503 "
                    + "seller.geo-unavailable when geo-service is down. Publishes seller.pickup_location.updated. "
                    + "Offers with fulfilment AFROTRANSACT_DELIVERY reference the location by id.")
    @PutMapping("/{id}/afrotransact-pickup")
    ShipFromAddressResponse pickupShipFrom(@PathVariable String orgId, @PathVariable UUID id,
                                           @Valid @RequestBody PickupRequest body) {
        return ShipFromAddressResponse.of(addresses.setPickup(orgId, id, body.toInput()));
    }

    @Operation(summary = "Make an address the default; returns every address")
    @PostMapping("/{id}/default")
    List<ShipFromAddressResponse> defaultShipFrom(@PathVariable String orgId, @PathVariable UUID id) {
        return ShipFromAddressResponse.all(addresses.makeDefault(orgId, id));
    }

    @Operation(summary = "Delete an address; returns the remaining ones (a new default is chosen when needed)")
    @DeleteMapping("/{id}")
    List<ShipFromAddressResponse> deleteShipFrom(@PathVariable String orgId, @PathVariable UUID id) {
        return ShipFromAddressResponse.all(addresses.delete(orgId, id));
    }
}
