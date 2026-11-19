package com.afrotransact.catalog.adapter.in.web;

import java.time.Instant;
import java.util.List;

import com.afrotransact.catalog.application.PictureRulesService;
import com.afrotransact.catalog.domain.pictures.PictureKnobs;
import com.afrotransact.catalog.domain.pictures.PictureOverrides;
import com.afrotransact.catalog.domain.pictures.PictureRules;
import com.afrotransact.catalog.domain.pictures.PictureRulesChange;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** The wire shapes of the picture-rules setting. The knob names are the same in Go ({@code picturerules.Knobs}). */
final class PictureRulesDtos {

    private PictureRulesDtos() {
    }

    @Schema(name = "PictureKnobs", description = "The individual picture rules in force")
    record KnobsDto(int minImages, int minLongSidePx, int minShortSidePx, int maxMegabytes, boolean checkBackground,
                    boolean checkAspect, boolean checkTextOverlay, boolean requireRealPhotoForStorefront,
                    boolean requirePhotoForMatching, boolean allowPlaceholders) {

        static KnobsDto of(PictureKnobs k) {
            return new KnobsDto(k.minImages(), k.minLongSidePx(), k.minShortSidePx(), k.maxMegabytes(),
                    k.checkBackground(), k.checkAspect(), k.checkTextOverlay(), k.requireRealPhotoForStorefront(),
                    k.requirePhotoForMatching(), k.allowPlaceholders());
        }
    }

    @Schema(name = "PictureOverrides", description = "Knobs changed on top of the profile; absent keeps the profile's value")
    record OverridesDto(Integer minImages, Integer minLongSidePx, Integer minShortSidePx, Integer maxMegabytes,
                        Boolean checkBackground, Boolean checkAspect, Boolean checkTextOverlay,
                        Boolean requireRealPhotoForStorefront, Boolean requirePhotoForMatching,
                        Boolean allowPlaceholders) {

        PictureOverrides toDomain() {
            return new PictureOverrides(minImages, minLongSidePx, minShortSidePx, maxMegabytes, checkBackground,
                    checkAspect, checkTextOverlay, requireRealPhotoForStorefront, requirePhotoForMatching,
                    allowPlaceholders);
        }

        static OverridesDto of(PictureOverrides o) {
            return new OverridesDto(o.minImages(), o.minLongSidePx(), o.minShortSidePx(), o.maxMegabytes(),
                    o.checkBackground(), o.checkAspect(), o.checkTextOverlay(), o.requireRealPhotoForStorefront(),
                    o.requirePhotoForMatching(), o.allowPlaceholders());
        }
    }

    @Schema(name = "PictureRules", description = "The picture rules in force (public read, used by every service)")
    record RulesDto(@Schema(allowableValues = {"strict", "startup", "testing"}) String profile, KnobsDto knobs,
                    @Schema(description = "Names of the knobs staff changed on top of the profile") List<String> overridden,
                    @Schema(description = "Accepted image content types under every profile") List<String> formats,
                    @Schema(description = "The stored version; 0 while the environment default is in force") long version,
                    @Schema(description = "The rule in plain words, for Seller Center messages") String summary) {

        static RulesDto of(PictureRules r) {
            return new RulesDto(r.profile().wire(), KnobsDto.of(r.knobs()), r.overridden(), PictureRules.FORMATS,
                    r.version(), r.summary());
        }
    }

    @Schema(name = "PictureRulesChange", description = "One audited change")
    record ChangeDto(long version, String previousProfile, String profile, List<String> overridden, String changedBy,
                     Instant changedAt, String reason) {

        static ChangeDto of(PictureRulesChange c) {
            return new ChangeDto(c.version(), c.previousProfile() == null ? null : c.previousProfile().wire(),
                    c.profile().wire(), c.overridden(), c.changedBy(), c.changedAt(), c.reason());
        }
    }

    @Schema(name = "PictureRulesView", description = "The staff screen: rules in force, who saved them, the profiles to "
            + "compare and the audit trail")
    record ViewDto(RulesDto rules, OverridesDto overrides, String updatedBy, Instant updatedAt, boolean canChange,
                   String environmentDefault, boolean relaxed, List<RulesDto> profiles, List<ChangeDto> history) {

        static ViewDto of(PictureRulesService.View v) {
            PictureRules r = v.rules();
            return new ViewDto(RulesDto.of(r), OverridesDto.of(r.overrides()), r.updatedBy(), r.updatedAt(),
                    v.canChange(), v.environmentDefault().wire(), r.relaxed(),
                    v.defaults().stream().map(RulesDto::of).toList(), v.history().stream().map(ChangeDto::of).toList());
        }
    }

    @Schema(name = "PictureRulesUpdate")
    record UpdateRequest(@NotBlank @Schema(allowableValues = {"strict", "startup", "testing"}) String profile,
                         OverridesDto overrides,
                         @NotBlank @Schema(description = "Why, 3 to 300 characters; logged with the staff member's name")
                         String reason) {
    }
}
