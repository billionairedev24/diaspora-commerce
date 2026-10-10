package com.afrotransact.orders.adapter.in.web;

import com.afrotransact.orders.application.OrderLookup;
import com.afrotransact.orders.application.OrderLookupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Order lookup", description = "Service-to-service order lookup (tax-returns, trust-ops)")
@RestController
class OrderLookupController {

    private final OrderLookupService lookups;

    OrderLookupController(OrderLookupService lookups) {
        this.lookups = lookups;
    }

    @Operation(summary = "Look up an order by id or number (SERVICE with orders:lookup; staff with orders:read)",
            description = "Customer id, payment id, the full ship-to address and every shipment with its lines "
                    + "(line ids = payments line ids), shipping, tax jurisdictions, ship-by date and return window. "
                    + "Used by tax-returns (returns, labels) and trust-ops (claim ownership, refunds). No handover "
                    + "codes.")
    @GetMapping("/v1/lookup/orders/{idOrNumber}")
    OrderLookup lookup(@PathVariable String idOrNumber) {
        return lookups.lookup(idOrNumber);
    }
}
