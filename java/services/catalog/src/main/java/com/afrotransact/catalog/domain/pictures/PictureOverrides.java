package com.afrotransact.catalog.domain.pictures;

import java.util.ArrayList;
import java.util.List;

/** The knobs staff changed on top of the profile; null keeps the profile's value. */
public record PictureOverrides(Integer minImages, Integer minLongSidePx, Integer minShortSidePx, Integer maxMegabytes,
                               Boolean checkBackground, Boolean checkAspect, Boolean checkTextOverlay,
                               Boolean requireRealPhotoForStorefront, Boolean requirePhotoForMatching,
                               Boolean allowPlaceholders) {

    public static PictureOverrides none() {
        return new PictureOverrides(null, null, null, null, null, null, null, null, null, null);
    }

    /** The knobs of the profile with these overrides on top. */
    public PictureKnobs applyTo(PictureKnobs base) {
        return new PictureKnobs(pick(minImages, base.minImages()), pick(minLongSidePx, base.minLongSidePx()),
                pick(minShortSidePx, base.minShortSidePx()), pick(maxMegabytes, base.maxMegabytes()),
                pick(checkBackground, base.checkBackground()), pick(checkAspect, base.checkAspect()),
                pick(checkTextOverlay, base.checkTextOverlay()),
                pick(requireRealPhotoForStorefront, base.requireRealPhotoForStorefront()),
                pick(requirePhotoForMatching, base.requirePhotoForMatching()),
                pick(allowPlaceholders, base.allowPlaceholders()));
    }

    /** Names of the overridden knobs in display order. */
    public List<String> names() {
        List<String> out = new ArrayList<>();
        Object[] values = {minImages, minLongSidePx, minShortSidePx, maxMegabytes, checkBackground, checkAspect,
                checkTextOverlay, requireRealPhotoForStorefront, requirePhotoForMatching, allowPlaceholders};
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null) {
                out.add(PictureKnobs.NAMES.get(i));
            }
        }
        return out;
    }

    private static int pick(Integer override, int base) {
        return override == null ? base : override;
    }

    private static boolean pick(Boolean override, boolean base) {
        return override == null ? base : override;
    }
}
