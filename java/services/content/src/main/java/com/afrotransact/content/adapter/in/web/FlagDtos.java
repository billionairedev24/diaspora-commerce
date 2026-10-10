package com.afrotransact.content.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.afrotransact.content.domain.flag.FeatureFlag;
import com.afrotransact.content.domain.flag.FlagTargeting;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Feature flag bodies. */
final class FlagDtos {

    private FlagDtos() {
    }

    @Schema(name = "FlagTargeting", description = "Empty lists mean any; allowList subjects always get the flag")
    record TargetingDto(List<String> markets, List<String> roles, List<String> allowList) {

        FlagTargeting toDomain() {
            return new FlagTargeting(set(markets), set(roles), set(allowList));
        }

        static TargetingDto of(FlagTargeting t) {
            return new TargetingDto(t.markets().stream().sorted().toList(), t.roles().stream().sorted().toList(),
                    t.allowList().stream().sorted().toList());
        }

        private static Set<String> set(List<String> in) {
            return in == null ? Set.of() : Set.copyOf(in);
        }
    }

    @Schema(name = "FeatureFlag")
    record FlagDto(String key, String description, boolean enabled, double rolloutPercentage, boolean killSwitch,
                   TargetingDto targeting, long version, Instant updatedAt, String updatedBy) {

        static FlagDto of(FeatureFlag f) {
            return new FlagDto(f.key(), f.description(), f.enabled(), f.rolloutPercentage(), f.killSwitch(),
                    TargetingDto.of(f.targeting()), f.version(), f.updatedAt(), f.updatedBy());
        }
    }

    @Schema(name = "CreateFlagRequest")
    record CreateFlagRequest(@NotBlank String key, @NotBlank String description, Boolean enabled,
                             @DecimalMin("0") @DecimalMax("100") Double rolloutPercentage, TargetingDto targeting) {
    }

    @Schema(name = "UpdateFlagRequest")
    record UpdateFlagRequest(@NotBlank String description, @NotNull Boolean enabled,
                             @NotNull @DecimalMin("0") @DecimalMax("100") Double rolloutPercentage, Boolean killSwitch,
                             TargetingDto targeting, @NotNull Long expectedVersion) {
    }

    @Schema(name = "KillSwitchRequest")
    record KillSwitchRequest(@NotNull Boolean on) {
    }

    @Schema(name = "FlagEvaluationContext")
    record EvaluationContextDto(@Schema(example = "US") String market,
                                @Schema(description = "Trusted only from service callers") List<String> roles) {
    }

    @Schema(name = "EvaluateFlagsRequest")
    record EvaluateRequest(@Schema(description = "Targeting key: user id or anonymous device id") String subject,
                           EvaluationContextDto context,
                           @Schema(description = "Only these flags (default: all)") List<String> flags) {
    }

    @Schema(name = "EvaluateFlagsResponse")
    record EvaluateResponse(Map<String, Boolean> flags, String etag) {
    }
}
