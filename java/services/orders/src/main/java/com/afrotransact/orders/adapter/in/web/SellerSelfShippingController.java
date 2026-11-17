package com.afrotransact.orders.adapter.in.web;

import java.util.Arrays;
import java.util.List;

import com.afrotransact.orders.application.SellerSelfShippingService;
import com.afrotransact.orders.application.SellerSelfShippingService.Setting;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Seller self-shipping", description = "A seller's own flat shipping charge per order, available only while the "
        + "staff switch sellerSelfShippingEnabled is on (off by default). The seller keeps it and is responsible for it.")
@RestController
class SellerSelfShippingController {

    private final SellerSelfShippingService service;

    SellerSelfShippingController(SellerSelfShippingService service) {
        this.service = service;
    }

    @Operation(summary = "My own shipping charge",
            description = "available is the staff switch (false: the option is not offered anywhere); enabled and chargeMinor "
                    + "are the seller's own setting. Staff name the organisation with org.")
    @GetMapping("/v1/seller/self-shipping")
    SettingResponse mine(@RequestParam(required = false) String org, @RequestParam(defaultValue = "USD") String currency) {
        return SettingResponse.of(service.mine(org, currency));
    }

    @Operation(summary = "Set my own shipping charge (flat, per order)",
            description = "409 orders.self-shipping-disabled while the staff switch is off. 422 "
                    + "orders.invalid-self-shipping-charge outside 0 to 100.00. One charge for each order package of this "
                    + "seller, never per product; the seller keeps it.")
    @PutMapping("/v1/seller/self-shipping")
    SettingResponse replace(@RequestParam(required = false) String org, @RequestParam(defaultValue = "USD") String currency,
                            @Valid @RequestBody SettingRequest body) {
        return SettingResponse.of(service.replace(org, currency, Boolean.TRUE.equals(body.enabled()),
                body.chargeMinor()));
    }

    @Operation(summary = "Sellers that ship themselves, with their charge (no sign-in)",
            description = "The sellers among orgs that opted in, for checkout. Empty while the staff switch is off.")
    @SecurityRequirements
    @GetMapping("/v1/public/self-shipping")
    OfferedResponse offered(@RequestParam String orgs, @RequestParam(defaultValue = "USD") String currency) {
        List<String> ids = Arrays.stream(orgs.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
        return new OfferedResponse(service.offered(ids, currency).stream().map(SettingResponse::of).toList());
    }

    record SettingRequest(Boolean enabled, @NotNull Long chargeMinor) {
    }

    record SettingResponse(String sellerOrgId, String currency, boolean available, boolean enabled, long chargeMinor,
                           long version) {

        static SettingResponse of(Setting s) {
            return new SettingResponse(s.sellerOrgId(), s.currency(), s.available(), s.enabled(), s.chargeMinor(),
                    s.version());
        }
    }

    record OfferedResponse(List<SettingResponse> items) {
    }
}
