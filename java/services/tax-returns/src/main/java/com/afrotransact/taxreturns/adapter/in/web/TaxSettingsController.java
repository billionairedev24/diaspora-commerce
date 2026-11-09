package com.afrotransact.taxreturns.adapter.in.web;

import java.time.Instant;
import java.util.List;

import com.afrotransact.taxreturns.application.TaxSettingsService;
import com.afrotransact.taxreturns.application.TaxSettingsService.TaxSettings;
import com.afrotransact.taxreturns.domain.tax.TaxMethod;
import com.afrotransact.taxreturns.domain.tax.TaxMethodChange;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Who calculates sales tax: our own rules or Stripe Tax (staff, tax:read / tax:configure). */
@RestController
@Tag(name = "Tax settings")
class TaxSettingsController {

    private final TaxSettingsService settings;

    TaxSettingsController(TaxSettingsService settings) {
        this.settings = settings;
    }

    @Schema(name = "TaxMethodChange")
    record ChangeDto(String from, String to, String reason, String changedBy, Instant changedAt) {

        static ChangeDto of(TaxMethodChange c) {
            return new ChangeDto(c.from() == null ? null : c.from().name(), c.to().name(), c.reason(), c.changedBy(),
                    c.changedAt());
        }
    }

    @Schema(name = "TaxSettings")
    record SettingsDto(@Schema(allowableValues = {"RULES", "STRIPE_TAX", "AVALARA"}) String method,
                       @Schema(description = "What applies until staff choose (TAX_PROVIDER)") String configuredDefault,
                       @Schema(description = "Stripe Tax can be chosen: its key is set") boolean stripeAvailable,
                       @Schema(description = "Avalara can be chosen: account, license key and company code are set")
                       boolean avalaraAvailable,
                       @Schema(description = "SHADOW mode: this provider is quoted next to the charging method on real "
                               + "orders and never charged; null = off", allowableValues = {"STRIPE", "AVALARA"})
                       String shadowProvider,
                       String changedBy, Instant changedAt, List<ChangeDto> history) {

        static SettingsDto of(TaxSettings s) {
            return new SettingsDto(s.method().name(), s.configuredDefault().name(), s.stripeAvailable(),
                    s.avalaraAvailable(), s.shadowProvider() == null ? null : s.shadowProvider().name(),
                    s.changedBy(), s.changedAt(), s.history().stream().map(ChangeDto::of).toList());
        }
    }

    record ChangeRequest(@NotNull TaxMethod method,
                         @Schema(description = "Turn SHADOW mode on for this provider, or null/absent to turn it off. "
                                 + "A PUT replaces both the method and the shadow provider.")
                         TaxProviderId shadowProvider, @NotBlank String reason) {
    }

    @Operation(summary = "The tax method in force and its history (tax:read)")
    @GetMapping("/v1/tax/settings")
    SettingsDto get() {
        return SettingsDto.of(settings.current());
    }

    @Operation(summary = "Switch who calculates sales tax and set the SHADOW provider (tax:configure)",
            description = "RULES is our own rules over the rate tables and outcome codes; STRIPE_TAX and AVALARA need "
                    + "the provider set up (422 tax.stripe-not-set-up / tax.avalara-not-set-up otherwise) and at least "
                    + "one mapped code (422 tax.provider-not-mapped). shadowProvider turns SHADOW mode on for a "
                    + "configured provider other than the method's. A reason is required and the change is recorded. "
                    + "It applies to new quotes; placed orders keep the tax they were charged.")
    @PutMapping("/v1/tax/settings")
    SettingsDto change(@Valid @RequestBody ChangeRequest body) {
        return SettingsDto.of(settings.change(body.method(), body.shadowProvider(), body.reason()));
    }
}
