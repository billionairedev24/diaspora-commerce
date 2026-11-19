package com.afrotransact.catalog.domain.pictures;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.afrotransact.catalog.domain.matching.RealPhoto;

/**
 * The picture rules in force: the profile, its knobs with the staff overrides applied, which knobs are overridden and
 * who saved them. This is the ONE definition Java enforces (publish gate, image gate, candidate matching, storefront
 * eligibility); Go services read the same document from {@code GET /v1/public/picture-rules}.
 *
 * @param version   the stored version, 0 while the environment default is in force
 * @param updatedBy the staff member's id (the console shows the name), null for the environment default
 */
public record PictureRules(PictureProfile profile, PictureOverrides overrides, PictureKnobs knobs, long version,
                           String updatedBy, Instant updatedAt) {

    /** The accepted image content types under every profile: not a knob. */
    public static final List<String> FORMATS = List.of("image/jpeg", "image/png", "image/webp", "image/avif");

    public static PictureRules resolve(PictureProfile profile, PictureOverrides overrides) {
        return new PictureRules(profile, overrides, overrides.applyTo(PictureKnobs.of(profile)), 0, null, null);
    }

    public static PictureRules defaults(PictureProfile profile) {
        return resolve(profile, PictureOverrides.none());
    }

    /** Today's documented standard, unchanged. */
    public static PictureRules strict() {
        return defaults(PictureProfile.STRICT);
    }

    public PictureRules savedBy(String who, Instant when, long nextVersion) {
        return new PictureRules(profile, overrides, knobs, nextVersion, who, when);
    }

    public List<String> overridden() {
        return overrides.names();
    }

    /** True while the strict standard is in force without any override. */
    public boolean isStrict() {
        return profile == PictureProfile.STRICT && overridden().isEmpty();
    }

    /** Whether any rule is looser than the strict standard (the staff console warns). */
    public boolean relaxed() {
        return !isStrict();
    }

    /** Whether the address counts as a photo: a real photo always, a placeholder only when the profile allows it. */
    public boolean photoOk(String imageUrl) {
        return switch (RealPhoto.classify(imageUrl)) {
            case REAL -> true;
            case PLACEHOLDER -> knobs.allowPlaceholders();
            case NONE -> false;
        };
    }

    /** The storefront rule: with the requirement on only a counted photo shows; off, everything shows. */
    public boolean showOnStorefront(String imageUrl) {
        return !knobs.requireRealPhotoForStorefront() || photoOk(imageUrl);
    }

    /** The candidate-matching rule: whether a page with this first picture may be offered to sellers. */
    public boolean matchableWithPhoto(String imageUrl) {
        return !knobs.requirePhotoForMatching() || photoOk(imageUrl);
    }

    /**
     * Whether media's verdicts (too small, not white, held for review, unknown to media) refuse a listing's pictures. Under
     * {@link PictureProfile#TESTING} they are recorded but refuse nothing, so test products with any picture can be saved.
     */
    public boolean verdictsBlock() {
        return profile != PictureProfile.TESTING;
    }

    /** The rule in plain words for sellers: "at least 1 photo, at least 500 px on the longest side, ...". */
    public String summary() {
        PictureKnobs k = knobs;
        List<String> parts = new ArrayList<>();
        parts.add(k.minImages() == 0 ? "photos are optional"
                : k.minImages() == 1 ? "at least 1 photo" : "at least " + k.minImages() + " photos");
        parts.add("at least " + k.minLongSidePx() + " px on the longest side");
        if (k.minShortSidePx() > 1) {
            parts.add("at least " + k.minShortSidePx() + " px on the shortest side");
        }
        parts.add("up to " + k.maxMegabytes() + " MB each");
        parts.add("JPG, PNG, WebP or AVIF");
        if (k.checkBackground()) {
            parts.add("main photo on a plain white background");
        }
        return String.join(", ", parts);
    }
}
