package com.afrotransact.bookings.adapter.in.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.bookings.application.ServiceAreaService.AreaCommand;
import com.afrotransact.bookings.application.ServiceAreaService.AreaView;
import com.afrotransact.bookings.application.ServiceAreaService.Preview;
import com.afrotransact.bookings.domain.AreaKind;
import com.afrotransact.bookings.domain.AreaPolicy;
import com.afrotransact.bookings.domain.NamedArea;
import com.afrotransact.bookings.domain.RadiusRule;
import com.afrotransact.bookings.domain.ServiceArea;
import com.afrotransact.bookings.domain.ServiceZone;
import com.afrotransact.bookings.domain.ZoneCity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Bodies of the service-area endpoints: provider (Provider Hub) and staff (policy, zones). */
final class ServiceAreaDtos {

    private ServiceAreaDtos() {
    }

    @Schema(name = "NamedArea")
    record NamedAreaDto(@NotNull AreaKind kind, @NotBlank @Size(max = 80) String value,
                        @Schema(description = "2-letter state, required for CITY") String state) {

        NamedArea toDomain() {
            return new NamedArea(kind, value, state);
        }

        static NamedAreaDto of(NamedArea a) {
            return new NamedAreaDto(a.kind(), a.value(), a.state());
        }
    }

    @Schema(name = "ServiceAreaRequest")
    record AreaRequest(@NotBlank @Pattern(regexp = "^\\d{5}$") String baseZip,
                       @Min(1) @Max(150) @Schema(description = "Miles; omit for the default of the provider's "
                               + "categories") Integer radiusMiles,
                       @Size(max = 20) List<@Valid NamedAreaDto> extraAreas) {

        AreaCommand toCommand() {
            return new AreaCommand(baseZip, radiusMiles, extraAreas == null ? List.of()
                    : extraAreas.stream().map(NamedAreaDto::toDomain).toList());
        }
    }

    @Schema(name = "CityRef")
    record CityDto(String city, String state) {

        static CityDto of(ZoneCity c) {
            return new CityDto(c.city(), c.state());
        }
    }

    @Schema(name = "RadiusRule")
    record RadiusRuleDto(@Min(1) @Max(150) int defaultMiles, @Min(1) @Max(150) int minMiles,
                         @Min(1) @Max(150) int maxMiles) {

        RadiusRule toDomain() {
            return new RadiusRule(defaultMiles, minMiles, maxMiles);
        }

        static RadiusRuleDto of(RadiusRule r) {
            return new RadiusRuleDto(r.defaultMiles(), r.minMiles(), r.maxMiles());
        }
    }

    @Schema(name = "AreaPolicy", description = "Staff-set default search radius by category (a default for "
            + "shoppers, never a limit) and the bounds of a provider's declared radius")
    record PolicyDto(@NotEmpty Map<String, @Valid RadiusRuleDto> byCategory, @NotNull @Valid RadiusRuleDto fallback) {

        AreaPolicy toDomain() {
            Map<String, RadiusRule> m = new LinkedHashMap<>();
            byCategory.forEach((k, v) -> m.put(k, v.toDomain()));
            return new AreaPolicy(m, fallback.toDomain());
        }

        static PolicyDto of(AreaPolicy p) {
            Map<String, RadiusRuleDto> m = new LinkedHashMap<>();
            p.byCategory().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e -> m.put(e.getKey(), RadiusRuleDto.of(e.getValue())));
            return new PolicyDto(m, RadiusRuleDto.of(p.fallback()));
        }
    }

    @Schema(name = "ServiceZone")
    record ZoneDto(@NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,39}$") String key,
                   @NotBlank @Size(max = 80) String name, @NotEmpty @Size(max = 200) List<@Valid CityDto> cities) {

        ServiceZone toDomain() {
            return new ServiceZone(key, name, cities.stream().map(c -> new ZoneCity(c.city(), c.state())).toList());
        }

        static ZoneDto of(ServiceZone z) {
            return new ZoneDto(z.key(), z.name(), z.cities().stream().map(CityDto::of).toList());
        }
    }

    @Schema(name = "ServiceArea")
    record AreaResponse(String baseZip, String baseCity, String baseState, int radiusMiles,
                        List<NamedAreaDto> extraAreas, List<CityDto> servedCities, String zone) {

        static AreaResponse of(ServiceArea a, List<ServiceZone> zones) {
            return new AreaResponse(a.baseZip(), a.baseCity(), a.baseState(), a.radiusMiles(),
                    a.extraAreas().stream().map(NamedAreaDto::of).toList(),
                    a.servedCities().stream().map(CityDto::of).toList(),
                    zones.stream().filter(z -> z.contains(a.baseCity(), a.baseState())).map(ServiceZone::name)
                            .findFirst().orElse(null));
        }
    }

    @Schema(name = "ServiceAreaView", description = "The saved area (null before the first save), the radius bounds "
            + "for this provider's services (how far they travel to customers) and the zones to choose from")
    record AreaViewResponse(AreaResponse area, RadiusRuleDto bounds, List<ZoneDto> zones,
                            @Schema(description = "Service categories with their staff-set radius rules (the "
                                    + "categories a service can be listed under)") List<CategoryRuleDto> categories) {

        static AreaViewResponse of(AreaView v) {
            return new AreaViewResponse(v.area() == null ? null : AreaResponse.of(v.area(), v.zones()),
                    RadiusRuleDto.of(v.bounds()),
                    v.zones().stream().map(ZoneDto::of).toList(),
                    v.policy().byCategory().entrySet().stream().sorted(Map.Entry.comparingByKey())
                            .map(e -> new CategoryRuleDto(e.getKey(), RadiusRuleDto.of(e.getValue()))).toList());
        }
    }

    @Schema(name = "CategoryRadiusRule")
    record CategoryRuleDto(String category, RadiusRuleDto rule) {
    }

    @Schema(name = "ServiceAreaPreview")
    record PreviewResponse(String baseCity, String baseState, String zone, List<CityDto> cities) {

        static PreviewResponse of(Preview p) {
            return new PreviewResponse(p.baseCity(), p.baseState(), p.zone().map(ServiceZone::name).orElse(null),
                    p.cities().stream().map(CityDto::of).toList());
        }
    }

    @Schema(name = "ReasonedPolicy")
    record PolicyChange(@NotNull @Valid PolicyDto policy, @NotBlank @Size(min = 5, max = 500) String reason) {
    }

    @Schema(name = "ReasonedZone")
    record ZoneChange(@NotNull @Valid ZoneDto zone, @NotBlank @Size(min = 5, max = 500) String reason) {
    }

    @Schema(name = "ServiceAreaAuditEntry")
    record AuditDto(String at, String actor, String subject, String reason) {
    }
}
