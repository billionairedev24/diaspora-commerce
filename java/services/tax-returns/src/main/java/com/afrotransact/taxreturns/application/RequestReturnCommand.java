package com.afrotransact.taxreturns.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.taxreturns.domain.returns.ReturnAddress;
import com.afrotransact.taxreturns.domain.returns.ReturnLineRequest;
import com.afrotransact.taxreturns.domain.returns.ReturnMethod;

/**
 * Start a return for items of one delivered shipment.
 *
 * @param orderId    order
 * @param shipmentId shipment the items came in
 * @param method     drop-off, driver pickup or no return (low value / report a problem)
 * @param lines      items, quantities, reasons and evidence
 * @param returnFrom the customer's address for the label (required for drop-off)
 */
public record RequestReturnCommand(UUID orderId, UUID shipmentId, ReturnMethod method, List<ReturnLineRequest> lines,
                                   ReturnAddress returnFrom) {

    public RequestReturnCommand {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
