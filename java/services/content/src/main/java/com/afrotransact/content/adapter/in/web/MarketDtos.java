package com.afrotransact.content.adapter.in.web;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.content.application.MarketService.PublishResult;
import com.afrotransact.content.domain.market.Capabilities;
import com.afrotransact.content.domain.market.Capability;
import com.afrotransact.content.domain.market.City;
import com.afrotransact.content.domain.market.Country;
import com.afrotransact.content.domain.market.Eligibility;
import com.afrotransact.content.domain.market.LocationStatus;
import com.afrotransact.content.domain.market.MarketChanges;
import com.afrotransact.content.domain.market.MarketStatus;
import com.afrotransact.content.domain.market.Region;
import com.afrotransact.content.port.MarketRepository.Publication;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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

    @Schema(name = "MarketCity")
    record CityDto(String name, LocationStatus status) {

        static CityDto of(City c) {
            return new CityDto(c.name(), c.status());
        }
    }

    @Schema(name = "MarketRegion")
    record RegionDto(String code, String name, String kind, CapabilitiesDto capabilities,
                     @Schema(description = "LIVE (marketplace on), PLANNED or OFF") LocationStatus status,
                     @Schema(description = "Every city is covered (listed cities are overrides); false = only cities "
                             + "listed LIVE") boolean everyCity,
                     @Schema(description = "Optimistic-lock version: send it back as If-Match") long version,
                     int cityCount, int liveCities, List<CityDto> cities) {

        static RegionDto of(Region r) {
            return new RegionDto(r.code(), r.name(), r.kind().name(), CapabilitiesDto.of(r.capabilities()), r.status(),
                    r.everyCity(), r.version(), r.cities().size(), (int) r.cityCount(LocationStatus.LIVE),
                    r.cities().stream().map(CityDto::of).toList());
        }
    }

    @Schema(name = "MarketCountry")
    record CountryDto(String code, String name, MarketStatus status, String launchNote, String currency,
                      boolean onboarding, boolean launchListOpen, int liveRegions, int plannedRegions,
                      @Schema(description = "Optimistic-lock version of the country: If-Match for its status")
                      long version, List<RegionDto> regions) {

        static CountryDto of(Country c) {
            return new CountryDto(c.code(), c.name(), c.status(), c.launchNote(), c.currency(), c.live(),
                    launchList(c), live(c), (int) c.regionCount(LocationStatus.PLANNED), c.version(),
                    c.regions().stream().map(RegionDto::of).toList());
        }
    }

    @Schema(name = "MarketCountrySummary", description = "For country pickers: every ISO country with its status")
    record CountrySummaryDto(String code, String name, MarketStatus status, String launchNote, boolean onboarding,
                             boolean launchListOpen, int regionCount, int liveRegions, int plannedRegions,
                             int cityCount, int liveCities, long version) {

        static CountrySummaryDto of(Country c) {
            return new CountrySummaryDto(c.code(), c.name(), c.status(), c.launchNote(), c.live(), launchList(c),
                    c.regions().size(), live(c), (int) c.regionCount(LocationStatus.PLANNED),
                    c.regions().stream().mapToInt(r -> r.cities().size()).sum(),
                    (int) c.regions().stream().mapToLong(r -> r.cityCount(LocationStatus.LIVE)).sum(), c.version());
        }
    }

    @Schema(name = "AddMarketRegionRequest", description = "A state / province picked from the gazetteer; it starts "
            + "PLANNED")
    record AddRegionRequest(@NotNull @Size(min = 1, max = 10) String code, @NotNull @Size(min = 1, max = 80) String name) {
    }

    @Schema(name = "MarketCityInput")
    record CityInput(@NotNull @Size(min = 1, max = 80) String name,
                     @Schema(description = "Defaults to PLANNED") LocationStatus status) {

        City toCity() {
            return new City(name, status == null ? LocationStatus.PLANNED : status);
        }
    }

    @Schema(name = "AddMarketCitiesRequest", description = "Cities to append (cities already listed keep their status)")
    record AddCitiesRequest(@NotEmpty @Size(max = 2000) List<@Valid CityInput> cities) {

        List<City> toCities() {
            return cities.stream().map(CityInput::toCity).toList();
        }
    }

    @Schema(name = "MarketCityStatusRequest")
    record CityStatusRequest(@NotEmpty @Size(max = 2000) List<String> names, @NotNull LocationStatus status) {
    }

    @Schema(name = "MarketCountries")
    record CountriesResponse(int version, List<CountrySummaryDto> countries) {
    }

    @Schema(name = "UpdateMarketCountryRequest")
    record UpdateCountryRequest(MarketStatus status, @Size(max = 500) String launchNote) {
    }

    @Schema(name = "CapabilityChangeRequest", description = "Only what is present is changed. status LIVE switches the "
            + "marketplace on, PLANNED / OFF switch it off. Marketplace off turns every other capability off; enabling "
            + "another capability needs the marketplace on. everyCity: cover every city, or only cities listed LIVE.")
    record CapabilityChangeRequest(Boolean marketplace, Boolean services, Boolean food, Boolean pickup,
                                   Boolean taxRegistration, LocationStatus status, Boolean everyCity) {

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
