package com.afrotransact.orders.adapter.in.web;

import com.afrotransact.orders.application.OrderLookup;
import com.afrotransact.orders.application.OrderLookupService;
import com.afrotransact.orders.application.PurchaseCheck;
import com.afrotransact.orders.application.PurchaseCheckService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Order lookup", description = "Service-to-service order lookup (tax-returns, trust-ops)")
@RestController
class OrderLookupController {

    private final OrderLookupService lookups;
    private final PurchaseCheckService purchases;

    OrderLookupController(OrderLookupService lookups, PurchaseCheckService purchases) {
        this.lookups = lookups;
        this.purchases = purchases;
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

    @Operation(summary = "Did the customer receive this product? (SERVICE with orders:purchase-check)",
            description = "S324 verified-purchase reviews: catalog-service asks before it accepts a review. "
                    + "delivered is true when a shipment of the customer's with a line for the product ATIN is "
                    + "DELIVERED; the delivering order is returned, never its contents or address.")
    @GetMapping("/v1/lookup/customers/{customerId}/purchases/{atin}")
    PurchaseCheck purchase(@PathVariable String customerId, @PathVariable String atin) {
        return purchases.check(customerId, atin);
    }
}
