package com.afrotransact.orders.adapter.in.web;

import java.time.LocalDate;

import com.afrotransact.orders.application.SellerAnalytics;
import com.afrotransact.orders.application.SellerAnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Seller analytics", description = "Seller Center dashboard figures (orders:read)")
@RestController
class SellerAnalyticsController {

    private final SellerAnalyticsService analytics;

    SellerAnalyticsController(SellerAnalyticsService analytics) {
        this.analytics = analytics;
    }

    @Operation(summary = "Sales, orders and checkout conversion by day for the token's organisation",
            description = "Every day of [from, to] (business time zone, inclusive; default the last 30 days, at most "
                    + "366) with sales (items − discounts of paid, not cancelled or returned shipments), units, paid "
                    + "orders, checkouts that reached payment and conversionRate = orders / checkouts; plus top "
                    + "products, paid orders by fulfilment and shipments waiting to ship.")
    @GetMapping("/v1/seller/analytics")
    SellerAnalytics sellerAnalytics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Staff only: seller organisation") @RequestParam(required = false) String org) {
        return analytics.analytics(org, from, to);
    }
}
