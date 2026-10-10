package com.afrotransact.content.adapter.in.web;

import java.time.Duration;
import java.util.Locale;

import com.afrotransact.content.adapter.in.web.MarketDtos.CountriesResponse;
import com.afrotransact.content.adapter.in.web.MarketDtos.CountryDto;
import com.afrotransact.content.adapter.in.web.MarketDtos.CountrySummaryDto;
import com.afrotransact.content.adapter.in.web.MarketDtos.EligibilityResponse;
import com.afrotransact.content.adapter.in.web.MarketDtos.LaunchListRequest;
import com.afrotransact.content.adapter.in.web.MarketDtos.LaunchListResponse;
import com.afrotransact.content.application.EligibilityService;
import com.afrotransact.content.application.LaunchListService;
import com.afrotransact.content.domain.newsletter.LaunchAudience;
import com.afrotransact.content.domain.newsletter.LaunchListEntry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Markets", description = "Published markets: eligibility for checkout and onboarding, country pickers, launch lists")
@SecurityRequirements
@RestController
@RequestMapping("/v1/markets")
class MarketPublicController {

    private static final CacheControl SHORT = CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic();

    private final EligibilityService eligibility;
    private final LaunchListService launchList;

    MarketPublicController(EligibilityService eligibility, LaunchListService launchList) {
        this.eligibility = eligibility;
        this.launchList = launchList;
    }

    @Operation(summary = "Can we sell / deliver / onboard here?",
            description = "Checkout and the BFF block delivery when deliveryAllowed is false (state switched off); "
                    + "onboarding rejects countries where onboardingAllowed is false and offers the launch list.")
    @GetMapping("/eligibility")
    ResponseEntity<EligibilityResponse> eligibility(@RequestParam String country,
                                                    @RequestParam(required = false) String state,
                                                    @RequestParam(required = false) String zip) {
        return ResponseEntity.ok().cacheControl(SHORT)
                .body(EligibilityResponse.of(eligibility.check(country, state, zip)));
    }

    @Operation(summary = "Every country with its status (onboarding pickers)")
    @GetMapping("/countries")
    ResponseEntity<CountriesResponse> countries() {
        return ResponseEntity.ok().cacheControl(SHORT).eTag("markets-v" + eligibility.version())
                .body(new CountriesResponse(eligibility.version(),
                        eligibility.countries().stream().map(CountrySummaryDto::of).toList()));
    }

    @Operation(summary = "A published country with its states and switches")
    @GetMapping("/countries/{code}")
    ResponseEntity<CountryDto> country(@PathVariable String code) {
        return ResponseEntity.ok().cacheControl(SHORT).body(CountryDto.of(eligibility.country(code)));
    }

    @Operation(summary = "Join a country's launch list (countries that are not live)")
    @PostMapping("/launch-list")
    @ResponseStatus(HttpStatus.ACCEPTED)
    LaunchListResponse join(@RequestBody LaunchListRequest body) {
        LaunchAudience audience = body.audience() == null || body.audience().isBlank() ? LaunchAudience.SELLER
                : LaunchAudience.valueOf(body.audience().trim().toUpperCase(Locale.ROOT));
        LaunchListEntry e = launchList.join(body.email(), body.country(), audience, Boolean.TRUE.equals(body.shoppingUpdates()));
        return new LaunchListResponse("joined", e.country(), e.audience().name());
    }
}
