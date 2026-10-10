package com.afrotransact.identity.adapter.in.web;

import java.util.List;

import com.afrotransact.identity.adapter.in.web.AdminDtos.ProviderAdminResponse;
import com.afrotransact.identity.adapter.in.web.AdminDtos.SetProviderEnabledRequest;
import com.afrotransact.identity.application.IdentityProviderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Identity providers", description = "Which social sign-in buttons appear (Developers › Identity providers)")
@RestController
class IdentityProviderController {

    private final IdentityProviderService providers;

    IdentityProviderController(IdentityProviderService providers) {
        this.providers = providers;
    }

    @Schema(name = "IdentityProvider")
    record ProviderResponse(String id, String displayName, String authorizationPath) {
    }

    @Operation(summary = "Enabled social providers for sign-in screens (public)")
    @SecurityRequirements
    @GetMapping("/v1/identity-providers")
    List<ProviderResponse> enabled() {
        return providers.enabledProviders().stream()
                .map(p -> new ProviderResponse(p.id(), p.displayName(), "/oauth2/authorization/" + p.id()))
                .toList();
    }

    @Operation(summary = "All providers with their switch and configuration state (access:read)")
    @GetMapping("/v1/admin/identity-providers")
    List<ProviderAdminResponse> all() {
        return providers.all().stream().map(ProviderAdminResponse::of).toList();
    }

    @Operation(summary = "Enable or disable a provider (access:admin)")
    @PutMapping("/v1/admin/identity-providers/{providerId}")
    ProviderAdminResponse set(@PathVariable String providerId, @Valid @RequestBody SetProviderEnabledRequest body) {
        return ProviderAdminResponse.of(providers.setEnabled(providerId, body.enabled()));
    }
}
