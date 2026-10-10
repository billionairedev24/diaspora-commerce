package com.afrotransact.fulfilment.application;

import java.time.LocalDate;

/**
 * Route builder input: plan every waiting shipment of a depot (FC packages of that node and seller pickups) into
 * routes per delivery zone.
 *
 * @param serviceDate  delivery day
 * @param depotNodeId  depot FC
 * @param maxDropoffs  maximum drop-offs per route
 * @param window       delivery window shown to drivers (e.g. 1:00–5:00 pm)
 */
public record BuildRoutesCommand(LocalDate serviceDate, String depotNodeId, int maxDropoffs, String window) {
}
