package com.afrotransact.bookings.adapter.in.web;

import java.util.List;

import com.afrotransact.bookings.adapter.in.web.ServiceAreaDtos.AreaRequest;
import com.afrotransact.bookings.adapter.in.web.ServiceAreaDtos.AreaViewResponse;
import com.afrotransact.bookings.adapter.in.web.ServiceAreaDtos.AuditDto;
import com.afrotransact.bookings.adapter.in.web.ServiceAreaDtos.PolicyChange;
import com.afrotransact.bookings.adapter.in.web.ServiceAreaDtos.PolicyDto;
import com.afrotransact.bookings.adapter.in.web.ServiceAreaDtos.PreviewResponse;
import com.afrotransact.bookings.adapter.in.web.ServiceAreaDtos.ZoneChange;
import com.afrotransact.bookings.adapter.in.web.ServiceAreaDtos.ZoneDto;
import com.afrotransact.bookings.application.ServiceAreaAdminService;
import com.afrotransact.bookings.application.ServiceAreaService;
import com.afrotransact.bookings.domain.ServiceZone;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Service areas", description = "Where providers work (base, radius, extra areas) and the staff settings "
        + "behind it (radius policy, metro zones). Services are in person only.")
@RestController
class ServiceAreaController {

    private final ServiceAreaService areas;
    private final ServiceAreaAdminService admin;

    ServiceAreaController(ServiceAreaService areas, ServiceAreaAdminService admin) {
        this.areas = areas;
        this.admin = admin;
    }

    @Operation(summary = "The provider's service area with radius bounds and zones (bookings:read)")
    @GetMapping("/v1/providers/{orgId}/service-area")
    AreaViewResponse area(@PathVariable String orgId) {
        return AreaViewResponse.of(areas.get(orgId));
    }

    @Operation(summary = "Save the service area (bookings:services:write)", description = "The base ZIP is placed "
            + "through geo-service; the radius must be within the staff-set bounds of the provider's categories "
            + "(422 bookings.invalid-service-area).")
    @PutMapping("/v1/providers/{orgId}/service-area")
    AreaViewResponse saveArea(@PathVariable String orgId, @Valid @RequestBody AreaRequest body) {
        return AreaViewResponse.of(areas.save(orgId, body.toCommand()));
    }

    @Operation(summary = "Cities a base ZIP and radius would cover, without saving (bookings:read)")
    @GetMapping("/v1/providers/{orgId}/service-area/preview")
    PreviewResponse preview(@PathVariable String orgId, @RequestParam String zip,
                            @RequestParam(defaultValue = "20") int radiusMiles) {
        return PreviewResponse.of(areas.preview(orgId, zip, radiusMiles));
    }

    @Operation(summary = "Staff: radius policy (bookings:service-areas:manage)")
    @GetMapping("/v1/staff/service-areas/policy")
    PolicyDto policy() {
        return PolicyDto.of(admin.policy());
    }

    @Operation(summary = "Staff: change the radius policy with a reason (bookings:service-areas:manage)")
    @PutMapping("/v1/staff/service-areas/policy")
    PolicyDto savePolicy(@Valid @RequestBody PolicyChange body) {
        return PolicyDto.of(admin.savePolicy(body.policy().toDomain(), body.reason()));
    }

    @Operation(summary = "Staff: metro zones (bookings:service-areas:manage)")
    @GetMapping("/v1/staff/service-areas/zones")
    List<ZoneDto> zones() {
        return admin.zones().stream().map(ZoneDto::of).toList();
    }

    @Operation(summary = "Staff: create or replace a metro zone with a reason (bookings:service-areas:manage)")
    @PutMapping("/v1/staff/service-areas/zones/{key}")
    ZoneDto saveZone(@PathVariable String key, @Valid @RequestBody ZoneChange body) {
        ServiceZone z = new ZoneDto(key, body.zone().name(), body.zone().cities()).toDomain();
        return ZoneDto.of(admin.saveZone(z, body.reason()));
    }

    @Operation(summary = "Staff: delete a metro zone with a reason (bookings:service-areas:manage)")
    @DeleteMapping("/v1/staff/service-areas/zones/{key}")
    void deleteZone(@PathVariable String key, @RequestParam String reason) {
        admin.deleteZone(key, reason);
    }

    @Operation(summary = "Staff: who changed policy and zones, and why (bookings:service-areas:manage)")
    @GetMapping("/v1/staff/service-areas/audit")
    List<AuditDto> audit(@RequestParam(defaultValue = "50") int limit) {
        return admin.audit(limit).stream().map(a -> new AuditDto(a.at(), a.actor(), a.subject(), a.reason())).toList();
    }
}
