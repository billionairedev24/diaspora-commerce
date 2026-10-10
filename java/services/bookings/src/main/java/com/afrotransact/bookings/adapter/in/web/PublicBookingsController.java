package com.afrotransact.bookings.adapter.in.web;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.bookings.adapter.in.web.ProviderDtos.AddOnDto;
import com.afrotransact.bookings.application.PublicBookingsService;
import com.afrotransact.bookings.application.PublicBookingsService.Listing;
import com.afrotransact.bookings.application.PublicBookingsService.Summary;
import com.afrotransact.bookings.domain.Provider;
import com.afrotransact.bookings.domain.ServiceMode;
import com.afrotransact.bookings.domain.ServiceOffering;
import com.afrotransact.bookings.domain.TeamMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Public bookings", description = "Anonymous shopper reads (web shop, customer app, Lola): find a "
        + "service, pick a day and a time, read the summary. Availability is computed live, never cached.")
@SecurityRequirements
@RestController
class PublicBookingsController {

    private final PublicBookingsService catalog;

    PublicBookingsController(PublicBookingsService catalog) {
        this.catalog = catalog;
    }

    @Schema(name = "PublicServiceListing")
    record ListingDto(UUID serviceId, String orgId, String name, String providerName, String description,
                      String category, int durationMinutes, long priceFromMinor, long depositMinor, String currency,
                      Set<ServiceMode> modes, double rating, String nextAvailable, String location) {

        static ListingDto of(Listing l) {
            ServiceOffering o = l.service();
            Provider p = l.provider();
            return new ListingDto(o.id(), o.orgId(), o.name(), p.displayName(), o.description(), o.category(),
                    o.durationMinutes(), o.priceMinor(), o.depositMinor(), o.currency(), o.modes(), 0,
                    l.nextAvailable() == null ? null : l.nextAvailable().toString(), p.location());
        }
    }

    @Schema(name = "PublicServiceSearch")
    record SearchResponse(List<ListingDto> services) {
    }

    @Schema(name = "PublicTeamMember")
    record MemberDto(UUID id, String displayName) {

        static List<MemberDto> of(List<TeamMember> team) {
            return team.stream().map(m -> new MemberDto(m.id(), m.displayName())).toList();
        }
    }

    @Schema(name = "PublicPolicy")
    record PolicyDto(String kind, String label, String text) {

        static PolicyDto of(Provider p) {
            return new PolicyDto(p.policy().kind().name(), p.policy().label(), p.policy().text());
        }
    }

    @Schema(name = "PublicService")
    record ServiceDto(UUID id, String orgId, String providerName, String name, String description, String category,
                      int durationMinutes, long priceMinor, long depositMinor, long balanceMinor, String currency,
                      Set<ServiceMode> modes, List<AddOnDto> addOns, PolicyDto policy, boolean instantBooking,
                      List<MemberDto> team) {

        static ServiceDto of(ServiceOffering o, Provider p, List<TeamMember> team) {
            return new ServiceDto(o.id(), o.orgId(), p.displayName(), o.name(), o.description(), o.category(),
                    o.durationMinutes(), o.priceMinor(), o.depositMinor(), o.balanceMinor(), o.currency(), o.modes(),
                    o.addOns().stream().map(AddOnDto::of).toList(), PolicyDto.of(p), p.rules().instantBooking(),
                    MemberDto.of(team));
        }
    }

    @Schema(name = "PublicProvider")
    record ProviderPageDto(String orgId, String displayName, String timeZone, String location, PolicyDto policy,
                           boolean instantBooking, List<ServiceDto> services, List<MemberDto> team) {
    }

    @Schema(name = "AvailableDay")
    record DayDto(LocalDate date, int slots) {
    }

    @Schema(name = "AvailableSlot")
    record SlotDto(Instant start, Instant end, List<UUID> memberIds) {
    }

    @Schema(name = "BookingSummary", description = "Deposit due now, balance after the appointment, the policy")
    record SummaryDto(UUID serviceId, String serviceName, String providerName, int durationMinutes, long priceMinor,
                      long depositNowMinor, long balanceLaterMinor, String currency, String mode, List<String> addOns,
                      String policy, String policyText, boolean instantBooking, long providerAnswersWithinMinutes) {

        static SummaryDto of(Summary s) {
            return new SummaryDto(s.serviceId(), s.serviceName(), s.providerName(), s.durationMinutes(),
                    s.priceMinor(), s.depositNowMinor(), s.balanceLaterMinor(), s.currency(), s.mode().name(),
                    s.addOns(), s.policy(), s.policyText(), s.instantBooking(), s.requestSlaMinutes());
        }
    }

    @Operation(summary = "Search services (name, description, category, provider) with the next free time")
    @GetMapping("/v1/public/services/search")
    ResponseEntity<SearchResponse> search(@RequestParam(required = false) String q,
                                          @RequestParam(required = false) String category,
                                          @RequestParam(required = false) String zip,
                                          @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic())
                .body(new SearchResponse(catalog.search(q, category, size).stream().map(ListingDto::of).toList()));
    }

    @Operation(summary = "Search services (alias used by ai-gateway and mcp-server)", deprecated = true)
    @GetMapping("/v1/services/search")
    ResponseEntity<SearchResponse> searchAlias(@RequestParam(required = false) String q,
                                               @RequestParam(required = false) String category,
                                               @RequestParam(required = false) String zip,
                                               @RequestParam(defaultValue = "10") int size) {
        return search(q, category, zip, size);
    }

    @Operation(summary = "A provider's booking page: services, policy and bookable team")
    @GetMapping("/v1/public/providers/{orgId}")
    ProviderPageDto provider(@PathVariable String orgId) {
        PublicBookingsService.ProviderPage page = catalog.provider(orgId);
        Provider p = page.provider();
        return new ProviderPageDto(p.orgId(), p.displayName(), p.timeZone().getId(), p.location(), PolicyDto.of(p),
                p.rules().instantBooking(), page.services().stream().map(o -> ServiceDto.of(o, p, page.team()))
                .toList(), MemberDto.of(page.team()));
    }

    @Operation(summary = "A service: duration, price, deposit, modes, add-ons, policy and who performs it")
    @GetMapping("/v1/public/services/{serviceId}")
    ServiceDto service(@PathVariable UUID serviceId) {
        PublicBookingsService.ServiceDetail d = catalog.service(serviceId);
        return ServiceDto.of(d.service(), d.provider(), d.team());
    }

    @Operation(summary = "Days with free times (service → day)")
    @GetMapping("/v1/public/services/{serviceId}/days")
    ResponseEntity<List<DayDto>> days(@PathVariable UUID serviceId,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                      LocalDate from,
                                      @RequestParam(defaultValue = "14") int days,
                                      @RequestParam(required = false) UUID memberId,
                                      @RequestParam(required = false) List<String> addOnIds) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(catalog.days(serviceId, from, days, memberId, addOnIds).stream()
                        .map(d -> new DayDto(d.date(), d.count())).toList());
    }

    @Operation(summary = "Free start times on a date (day → time); only bookable slots are offered")
    @GetMapping("/v1/public/services/{serviceId}/slots")
    ResponseEntity<List<SlotDto>> slots(@PathVariable UUID serviceId,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                        @RequestParam(required = false) UUID memberId,
                                        @RequestParam(required = false) List<String> addOnIds) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(catalog.slots(serviceId, date, memberId, addOnIds).stream()
                        .map(s -> new SlotDto(s.start(), s.end(), s.memberIds())).toList());
    }

    @Operation(summary = "Booking summary: deposit now, balance later and the provider's policy")
    @GetMapping("/v1/public/services/{serviceId}/summary")
    SummaryDto summary(@PathVariable UUID serviceId, @RequestParam(required = false) List<String> addOnIds,
                       @RequestParam(required = false) ServiceMode mode) {
        return SummaryDto.of(catalog.summary(serviceId, addOnIds, mode));
    }
}
