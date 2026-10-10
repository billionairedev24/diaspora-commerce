package com.afrotransact.fulfilment.application;

import java.time.LocalDate;
import java.util.List;

/** A route created by hand in the dispatch console. */
public record CreateRouteCommand(String name, String zone, LocalDate serviceDate, String window, String depotNodeId,
                                 List<String> shipmentIds) {

    public CreateRouteCommand {
        shipmentIds = shipmentIds == null ? List.of() : List.copyOf(shipmentIds);
    }
}
