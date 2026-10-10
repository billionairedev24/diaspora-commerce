package com.afrotransact.inventory.domain;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * A stock-holding node (fulfilment centre, origin hub or warehouse).
 *
 * @param id               short code, for example {@code HOU-FC}
 * @param timezone         IANA zone; lot expiry and markdown use the node-local date
 * @param temperatureZones storage zones such as Ambient, Chilled, Hazmat
 */
public record Node(String id, String name, NodeKind kind, String country, String timezone,
                   List<String> temperatureZones, boolean active) {

    public Node {
        if (id == null || !id.matches("[A-Z0-9][A-Z0-9-]{1,19}")) {
            throw new InventoryRuleViolation("inventory.invalid-node", "Node code must be 2-20 upper-case letters, "
                    + "digits or dashes");
        }
        ZoneId.of(timezone);
        temperatureZones = temperatureZones == null ? List.of() : List.copyOf(temperatureZones);
    }

    /** Today's date at this node. */
    public LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(ZoneId.of(timezone)));
    }
}
