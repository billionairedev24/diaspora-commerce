package com.afrotransact.seller.application;

import java.util.List;

import com.afrotransact.seller.domain.PickupWindow;

/**
 * Turn AfroTransact pickup (S255) on or off for a ship-from location.
 *
 * @param hours   pickup windows (required to enable)
 * @param version the address version the client edited (null skips the check)
 */
public record PickupInput(boolean enabled, List<PickupWindow> hours, Long version) {

    public PickupInput {
        hours = hours == null ? List.of() : List.copyOf(hours);
    }
}
