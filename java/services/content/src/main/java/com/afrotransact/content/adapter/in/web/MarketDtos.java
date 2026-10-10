package com.afrotransact.content.adapter.in.web;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.content.application.MarketService.PublishResult;
import com.afrotransact.content.domain.market.Capabilities;
import com.afrotransact.content.domain.market.Capability;
import com.afrotransact.content.domain.market.Country;
import com.afrotransact.content.domain.market.Eligibility;
import com.afrotransact.content.domain.market.MarketChanges;
import com.afrotransact.content.domain.market.MarketStatus;
import com.afrotransact.content.domain.market.Region;
import com.afrotransact.content.port.MarketRepository.Publication;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the markets endpoints. */
final class MarketDtos {

    private MarketDtos() {
    }

    @Schema(name = "Capabilities")
    record CapabilitiesDto(boolean marketplace, boolean services, boolean food, boolean pickup,
                           boolean taxRegistration) {

        static CapabilitiesDto of(Capabilities c) {
            return new CapabilitiesDto(c.marketplace(), c.services(), c.food(), c.pickup(), c.taxRegistration());
        }
    }

    @Schema(name = "MarketRegion")
    record RegionDto(String code, String name, String kind, CapabilitiesDto capabilities) {

        static RegionDto of(Region r) {
            return new RegionDto(r.code(), r.name(), r.kind().name(), CapabilitiesDto.of(r.capabilities()));
        }
    }

    @Schema(name = "MarketCountry")
    record CountryDto(String code, String name, MarketStatus status, String launchNote, String currency,
                      boolean onboarding, boolean launchListOpen, int liveRegions, List<RegionDto> regions) {

        static CountryDto of(Country c) {
            return new CountryDto(c.code(), c.name(), c.status(), c.launchNote(), c.currency(), c.live(),
                    launchList(c), live(c), c.regions().stream().map(RegionDto::of).toList());
        }
    }

    @Schema(name = "MarketCountrySummary", description = "For country pickers: every ISO country with its status")
    record CountrySummaryDto(String code, String name, MarketStatus status, String launchNote, boolean onboarding,
                             boolean launchListOpen, int regionCount, int liveRegions) {

        static CountrySummaryDto of(Country c) {
            return new CountrySummaryDto(c.code(), c.name(), c.status(), c.launchNote(), c.live(), launchList(c),
                    c.regions().size(), live(c));
        }
    }

    @Schema(name = "MarketCountries")
    record CountriesResponse(int version, List<CountrySummaryDto> countries) {
    }

    @Schema(name = "UpdateMarketCountryRequest")
    record UpdateCountryRequest(MarketStatus status, @Size(max = 500) String launchNote) {
    }

    @Schema(name = "CapabilityChangeRequest", description = "Only the switches present are changed. Marketplace off "
            + "turns every other capability off; enabling another capability needs the marketplace on.")
    record CapabilityChangeRequest(Boolean marketplace, Boolean services, Boolean food, Boolean pickup,
                                   Boolean taxRegistration) {

        Map<Capability, Boolean> changes() {
            Map<Capability, Boolean> m = new EnumMap<>(Capability.class);
            put(m, Capability.MARKETPLACE, marketplace);
            put(m, Capability.SERVICES, services);
            put(m, Capability.FOOD, food);
            put(m, Capability.PICKUP, pickup);
            put(m, Capability.TAX_REGISTRATION, taxRegistration);
            return m;
        }

        private static void put(Map<Capability, Boolean> m, Capability c, Boolean v) {
            if (v != null) {
                m.put(c, v);
            }
        }
    }

    @Schema(name = "PublishMarketsRequest")
    record PublishRequest(@Size(max = 500) String note) {
    }

    @Schema(name = "MarketChanges")
    record ChangesDto(boolean pending, List<String> changedCountries, List<String> newlyLive,
                      List<String> disabledRegions, List<String> enabledRegions) {

        static ChangesDto of(MarketChanges c) {
            return new ChangesDto(!c.empty(), c.changedCountries(), c.newlyLive(), c.disabledRegions(),
                    c.enabledRegions());
        }
    }

    @Schema(name = "MarketPublication")
    record PublicationDto(int version, Instant publishedAt, String publishedBy, String note) {

        static PublicationDto of(Publication p) {
            return new PublicationDto(p.version(), p.publishedAt(), p.publishedBy(), p.note());
        }
    }

    @Schema(name = "PublishMarketsResponse")
    record PublishResponse(int version, ChangesDto changes) {

        static PublishResponse of(PublishResult r) {
            return new PublishResponse(r.version(), ChangesDto.of(r.changes()));
        }
    }

    @Schema(name = "Eligibility")
    record EligibilityResponse(String country, MarketStatus countryStatus, String region, boolean onboardingAllowed,
                               boolean deliveryAllowed, CapabilitiesDto capabilities,
                               @Schema(example = "region-disabled") String reason, String message, String launchNote,
                               boolean launchListOpen, int marketsVersion) {

        static EligibilityResponse of(Eligibility e) {
            return new EligibilityResponse(e.country(), e.countryStatus(), e.region(), e.onboardingAllowed(),
                    e.deliveryAllowed(), CapabilitiesDto.of(e.capabilities()), e.reason(), e.message(), e.launchNote(),
                    e.launchListOpen(), e.marketsVersion());
        }
    }

    @Schema(name = "LaunchListRequest")
    record LaunchListRequest(String email, String country,
                             @Schema(description = "SELLER (default), PROVIDER or SHOPPER") String audience,
                             Boolean shoppingUpdates) {
    }

    @Schema(name = "LaunchListResponse")
    record LaunchListResponse(String status, String country, String audience) {
    }

    private static boolean launchList(Country c) {
        return c.status() == MarketStatus.COMING_SOON || c.status() == MarketStatus.PLANNED;
    }

    private static int live(Country c) {
        return (int) c.regions().stream().filter(r -> r.capabilities().marketplace()).count();
    }
}
