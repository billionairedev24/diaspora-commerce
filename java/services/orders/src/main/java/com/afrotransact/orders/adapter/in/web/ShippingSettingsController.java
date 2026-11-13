package com.afrotransact.orders.adapter.in.web;

import java.time.Instant;
import java.util.List;

import com.afrotransact.platform.security.StaffOrServiceOnly;
import com.afrotransact.orders.application.ShippingSettingsCommand;
import com.afrotransact.orders.application.ShippingSettingsService;
import com.afrotransact.orders.domain.ShippingSettings;
import com.afrotransact.orders.domain.ShippingSettingsChange;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Shipping settings", description = "The one flat delivery fee AfroTransact charges at launch (listing "
        + "rules D16), its optional free-above threshold and the seller carrier switch: read by the shopper estimate "
        + "and by checkout, changed by staff with a reason (orders:shipping.view, orders:shipping.edit)")
@RestController
class ShippingSettingsController {

    private final ShippingSettingsService settings;

    ShippingSettingsController(ShippingSettingsService settings) {
        this.settings = settings;
    }

    @Operation(summary = "What delivery costs (no sign-in)",
            description = "The settings checkout charges by: flatFeeMinor for Delivery by AfroTransact and for "
                    + "AfroTransact collecting from a store, freeAboveMinor (null = never free) and "
                    + "sellerCarrierShippingEnabled (false at launch). Pickup is always 0.")
    @SecurityRequirements
    @GetMapping("/v1/public/shipping-settings")
    SettingsResponse publicSettings(@RequestParam(defaultValue = "USD") String currency) {
        return SettingsResponse.of(settings.view(currency));
    }

    @StaffOrServiceOnly
    @Operation(summary = "Change the shipping settings (orders:shipping.edit)",
            description = "A reason of 10 to 500 characters is required (422 orders.reason-required) and is logged with "
                    + "who and when. expectedVersion is the version the editor saw (409 "
                    + "orders.shipping-settings-version-mismatch when it changed). A save that changes nothing makes "
                    + "no new version. Orders already placed keep what they were charged.")
    @PutMapping("/v1/shipping-settings/{currency}")
    SettingsResponse replace(@PathVariable String currency, @Valid @RequestBody SettingsRequest body) {
        return SettingsResponse.of(settings.replace(currency, new ShippingSettingsCommand(body.flatFeeMinor(),
                body.freeAboveMinor(), Boolean.TRUE.equals(body.sellerCarrierShippingEnabled()), body.reason()),
                body.expectedVersion()));
    }

    @StaffOrServiceOnly
    @Operation(summary = "Audit trail of the shipping settings, newest first (orders:shipping.view)",
            description = "Every version with the settings before and after, who changed it, when and why.")
    @GetMapping("/v1/shipping-settings/{currency}/changes")
    ChangesResponse changes(@PathVariable String currency, @RequestParam(defaultValue = "50") int limit) {
        return ChangesResponse.of(settings.changes(currency, limit));
    }

    record SettingsRequest(@NotNull Long flatFeeMinor, Long freeAboveMinor, Boolean sellerCarrierShippingEnabled,
                           String reason, Long expectedVersion) {
    }

    record SettingsResponse(String currency, long version, long flatFeeMinor, Long freeAboveMinor,
                            boolean sellerCarrierShippingEnabled, Instant updatedAt, String updatedBy, String reason) {

        static SettingsResponse of(ShippingSettings s) {
            return new SettingsResponse(s.currency(), s.version(), s.flatFeeMinor(), s.freeAboveMinor(),
                    s.sellerCarrierShippingEnabled(), s.updatedAt(), s.updatedBy(), s.reason());
        }
    }

    record ChangeResponse(String currency, long version, SettingsResponse before, SettingsResponse after,
                          String changedBy, String reason, Instant changedAt) {

        static ChangeResponse of(ShippingSettingsChange c) {
            return new ChangeResponse(c.currency(), c.version(), c.before() == null ? null
                    : SettingsResponse.of(c.before()), SettingsResponse.of(c.after()), c.actorId(), c.reason(),
                    c.changedAt());
        }
    }

    record ChangesResponse(List<ChangeResponse> items) {

        static ChangesResponse of(List<ShippingSettingsChange> changes) {
            return new ChangesResponse(changes.stream().map(ChangeResponse::of).toList());
        }
    }
}
