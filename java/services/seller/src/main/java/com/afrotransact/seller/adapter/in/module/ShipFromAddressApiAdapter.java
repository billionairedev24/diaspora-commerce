package com.afrotransact.seller.adapter.in.module;

import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.seller.api.ShipFromAddressApi;
import com.afrotransact.seller.application.ShipFromAddressService;
import com.afrotransact.seller.domain.ShipFromAddress;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link ShipFromAddressApi}: the use cases behind the two ship-from endpoints, entered under the
 * calling module's principal. {@link ShipFromAddressService} checks {@code sellers:ship-from.read} as for a token.
 */
@Component
class ShipFromAddressApiAdapter implements ShipFromAddressApi {

    private final ShipFromAddressService addresses;
    private final ModuleEntry entry;

    ShipFromAddressApiAdapter(ShipFromAddressService addresses, ActorResolver actors) {
        this.addresses = addresses;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public Optional<ShipFromPlace> location(String locationId) {
        return entry.run(() -> {
            try {
                return Optional.of(place(addresses.location(UUID.fromString(locationId))));
            } catch (IllegalArgumentException | NotFoundException ex) {
                return Optional.<ShipFromPlace>empty();
            }
        });
    }

    @Override
    public Optional<ShipFromPlace> defaultLocation(String orgId) {
        return entry.run(() -> addresses.defaultLocation(orgId).map(ShipFromAddressApiAdapter::place));
    }

    private static ShipFromPlace place(ShipFromAddress a) {
        return new ShipFromPlace(String.valueOf(a.id()), a.orgId(), a.line1(), a.city(), a.state(), a.postalCode(),
                a.country(), a.pickup().enabled(), a.position() == null ? null : a.position().lat(),
                a.position() == null ? null : a.position().lng());
    }
}
