package com.afrotransact.payments.application;

import java.util.List;

import com.afrotransact.payments.domain.Money;

/**
 * Capture one shipment.
 *
 * @param lineIds      order lines in the shipment (empty = every line not captured yet)
 * @param amount       expected amount (Σ items + shipping + tax of the lines); null = computed
 * @param finalCapture release whatever is left of the authorisation after this capture
 */
public record CaptureCommand(String shipmentId, List<String> lineIds, Money amount, boolean finalCapture) {

    public CaptureCommand {
        lineIds = lineIds == null ? List.of() : List.copyOf(lineIds);
    }
}
