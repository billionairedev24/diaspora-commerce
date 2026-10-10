package com.afrotransact.content.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.afrotransact.content.domain.placement.Audience;
import com.afrotransact.content.domain.placement.Device;
import com.afrotransact.content.domain.placement.FrequencyCap;
import com.afrotransact.content.domain.placement.Placement;
import com.afrotransact.content.domain.placement.Placement.PlacementContent;
import com.afrotransact.content.domain.placement.Schedule;
import com.afrotransact.content.domain.placement.Slot;
import com.afrotransact.content.domain.placement.Sponsorship;
import com.afrotransact.content.domain.placement.Targeting;
import com.fasterxml.jackson.annotation.JsonRawValue;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

/** Request and response bodies of the placement endpoints. */
final class PlacementDtos {

    private PlacementDtos() {
    }

    @Schema(name = "PlacementTargeting", description = "Empty lists mean any")
    record TargetingDto(List<String> markets, List<String> communities, List<String> zipPrefixes, List<String> dmas,
                        List<Device> devices, Audience audience, List<String> categories) {

        Targeting toDomain() {
            return new Targeting(set(markets), set(communities), set(zipPrefixes), set(dmas),
                    devices == null ? null : Set.copyOf(devices), audience, set(categories));
        }

        static TargetingDto of(Targeting t) {
            return new TargetingDto(List.copyOf(t.markets()), List.copyOf(t.communities()), List.copyOf(t.zipPrefixes()),
                    List.copyOf(t.dmas()), t.devices().stream().sorted().toList(), t.audience(),
                    List.copyOf(t.categories()));
        }
    }

    @Schema(name = "PlacementSchedule")
    record ScheduleDto(@NotNull Instant startsAt, Instant endsAt) {

        static ScheduleDto of(Schedule s) {
            return new ScheduleDto(s.startsAt(), s.endsAt());
        }
    }

    @Schema(name = "FrequencyCap", description = "impressions 0 = uncapped")
    record FrequencyCapDto(Integer impressions, Integer windowHours) {

        static FrequencyCapDto of(FrequencyCap c) {
            return new FrequencyCapDto(c.impressions(), c.windowHours());
        }
    }

    @Schema(name = "PlacementRequest")
    record PlacementRequest(@Schema(example = "hero_carousel") String slot, @NotBlank String name,
                            @NotNull @Schema(types = "object", implementation = Object.class,
                                    description = "Must satisfy the slot's payload schema")
                            JsonNode payload,
                            TargetingDto targeting, @NotNull ScheduleDto schedule, FrequencyCapDto frequencyCap,
                            Integer priority, Boolean paid, String advertiser,
                            @Schema(description = "Required on update: the version that was loaded") Long expectedVersion) {

        PlacementContent toContent() {
            return new PlacementContent(name, payload.toString(),
                    targeting == null ? Targeting.everyone() : targeting.toDomain(),
                    new Schedule(schedule.startsAt(), schedule.endsAt()),
                    frequencyCap == null ? FrequencyCap.unlimited()
                            : new FrequencyCap(nz(frequencyCap.impressions()), nz(frequencyCap.windowHours())),
                    priority == null ? 0 : priority,
                    new Sponsorship(Boolean.TRUE.equals(paid), advertiser));
        }
    }

    @Schema(name = "Placement")
    record PlacementResponse(String id, String slot, String name, String status,
                             @JsonRawValue @Schema(types = "object") String payload, TargetingDto targeting,
                             ScheduleDto schedule, FrequencyCapDto frequencyCap, int priority, boolean paid,
                             String advertiser, String label, String createdBy, String submittedBy, String approvedBy,
                             String reviewNote, long version, Instant createdAt, Instant updatedAt) {

        static PlacementResponse of(Placement p) {
            return new PlacementResponse(p.id(), p.slot().key(), p.name(), p.status().name(), p.payload(),
                    TargetingDto.of(p.targeting()), ScheduleDto.of(p.schedule()), FrequencyCapDto.of(p.frequencyCap()),
                    p.priority(), p.sponsorship().paid(), p.sponsorship().advertiser(), p.label(), p.createdBy(),
                    p.submittedBy(), p.approvedBy(), p.reviewNote(), p.version(), p.createdAt(), p.updatedAt());
        }
    }

    @Schema(name = "Slot")
    record SlotResponse(String key, String name, String description, int maxItems, String sizeSpec,
                        @JsonRawValue @Schema(types = "object", description = "JSON Schema 2020-12") String payloadSchema) {

        static SlotResponse of(Slot s) {
            return new SlotResponse(s.key().key(), s.name(), s.description(), s.maxItems(), s.sizeSpec(), s.payloadSchema());
        }
    }

    @Schema(name = "RejectPlacementRequest")
    record RejectRequest(@NotBlank String note) {
    }

    @Schema(name = "ServedPlacement", description = "A placement as delivered to storefront and apps")
    record ServedPlacement(String id, String slot, int priority, String label, String advertiser,
                           @JsonRawValue @Schema(types = "object") String payload, FrequencyCapDto frequencyCap,
                           Instant startsAt, Instant endsAt) {

        static ServedPlacement of(Placement p) {
            return new ServedPlacement(p.id(), p.slot().key(), p.priority(), p.label(), p.sponsorship().advertiser(),
                    p.payload(), FrequencyCapDto.of(p.frequencyCap()), p.schedule().startsAt(), p.schedule().endsAt());
        }
    }

    @Schema(name = "ResolvedPlacements")
    record ResolveResponse(String market, String slot, List<ServedPlacement> placements) {
    }

    @Schema(name = "BundledPlacement", description = "Served placement plus targeting for edge-side filtering")
    record BundledPlacement(String id, int priority, String label, String advertiser,
                            @JsonRawValue @Schema(types = "object") String payload, FrequencyCapDto frequencyCap,
                            TargetingDto targeting, Instant startsAt, Instant endsAt) {

        static BundledPlacement of(Placement p) {
            return new BundledPlacement(p.id(), p.priority(), p.label(), p.sponsorship().advertiser(), p.payload(),
                    FrequencyCapDto.of(p.frequencyCap()), TargetingDto.of(p.targeting()), p.schedule().startsAt(),
                    p.schedule().endsAt());
        }
    }

    @Schema(name = "PlacementBundle", description = "Compiled per-market JSON pushed to the CDN; purged on publish")
    record BundleResponse(String market, Instant generatedAt, String etag, Map<String, List<BundledPlacement>> slots) {
    }

    private static int nz(Integer i) {
        return i == null ? 0 : i;
    }

    private static Set<String> set(List<String> in) {
        return in == null ? null : Set.copyOf(in);
    }
}
