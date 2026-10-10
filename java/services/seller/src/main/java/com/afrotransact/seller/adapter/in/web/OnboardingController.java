package com.afrotransact.seller.adapter.in.web;

import java.util.Locale;

import com.afrotransact.seller.adapter.in.web.OnboardingDtos.AccountSessionResponse;
import com.afrotransact.seller.adapter.in.web.OnboardingDtos.ApplicationResponse;
import com.afrotransact.seller.adapter.in.web.OnboardingDtos.LaunchListRequest;
import com.afrotransact.seller.adapter.in.web.OnboardingDtos.LaunchListResponse;
import com.afrotransact.seller.adapter.in.web.OnboardingDtos.MarketsResponse;
import com.afrotransact.seller.adapter.in.web.OnboardingDtos.StartRequest;
import com.afrotransact.seller.application.OnboardingService;
import com.afrotransact.seller.application.StartResult;
import com.afrotransact.seller.application.StripeOnboardingService;
import com.afrotransact.seller.port.AccountSession;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Onboarding", description = "The applicant's resumable application: start (prefilled from the signed-in "
        + "account), autosave each step, Stripe verification, submit. No back-office access until final approval.")
@RestController
class OnboardingController {

    private final OnboardingService onboarding;
    private final StripeOnboardingService stripe;

    OnboardingController(OnboardingService onboarding, StripeOnboardingService stripe) {
        this.onboarding = onboarding;
        this.stripe = stripe;
    }

    @Operation(summary = "Start or resume my application",
            description = "Creates a DRAFT organisation (caller = owner) and application; name, email and phone are "
                    + "prefilled from the token's identity claims and listed in prefilledFields. Returns 200 with the "
                    + "existing draft when one exists (resume).")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "200", description = "Existing application resumed")
    @PostMapping("/v1/applications/me")
    ResponseEntity<ApplicationResponse> startApplication(@Valid @RequestBody StartRequest body) {
        StartResult r = onboarding.start(Inputs.kind(body.kind()));
        return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApplicationResponse.of(r.application()));
    }

    @Operation(summary = "My draft with its current step and progress (resume after Save & sign out)")
    @GetMapping("/v1/applications/me")
    ApplicationResponse myApplication() {
        return ApplicationResponse.of(onboarding.mine());
    }

    @Operation(summary = "Submit (or resubmit after a request for info)",
            description = "Validates every step; 422 seller.application-incomplete lists every failing field.")
    @PostMapping("/v1/applications/me/submit")
    ApplicationResponse submitApplication() {
        return ApplicationResponse.of(onboarding.submit());
    }

    @Operation(summary = "Account Session for Stripe's embedded onboarding / account-management components",
            description = "Creates the platform-controlled connected account on first use (no Stripe dashboard, "
                    + "manual payouts). Returns client_secret for Connect.js.")
    @PostMapping("/v1/applications/me/stripe/account-session")
    AccountSessionResponse createAccountSession() {
        AccountSession s = stripe.accountSession();
        return new AccountSessionResponse(s.accountId(), s.clientSecret(), s.expiresAt());
    }

    @Operation(summary = "Refresh Stripe verification results (identity, IRS name/TIN match, requirements)")
    @PostMapping("/v1/applications/me/stripe/refresh")
    ApplicationResponse refreshStripeVerification() {
        return ApplicationResponse.of(stripe.refreshVerification());
    }

    @Operation(summary = "Join the launch list of a country that is not live (public)")
    @PostMapping("/v1/launch-list")
    ResponseEntity<LaunchListResponse> joinLaunchList(@Valid @RequestBody LaunchListRequest body) {
        boolean added = onboarding.joinLaunchList(body.country(), body.email(),
                Boolean.TRUE.equals(body.shoppingUpdates()));
        return ResponseEntity.status(added ? HttpStatus.CREATED : HttpStatus.OK).body(new LaunchListResponse(
                body.country().toUpperCase(Locale.ROOT), added,
                "We'll email you when AfroTransact opens for sellers there."));
    }

    @Operation(summary = "Countries open to sellers (public)")
    @GetMapping("/v1/markets")
    MarketsResponse liveMarkets() {
        return new MarketsResponse(onboarding.liveCountries());
    }
}
