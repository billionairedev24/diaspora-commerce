package com.afrotransact.catalog.domain.pictures;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The individual picture rules.
 *
 * @param minImages                     pictures a listing needs before it can be published (0: none)
 * @param minLongSidePx                 the longest side of the main picture (the zoom rule)
 * @param minShortSidePx                the shortest side of every picture
 * @param maxMegabytes                  one file's size cap (the platform ceiling of 20 always applies)
 * @param checkBackground               plain white background and a product that fills the frame (main picture)
 * @param checkAspect                   warn about very wide or very tall pictures
 * @param checkTextOverlay              hold pictures with text, logos or watermarks for review
 * @param requireRealPhotoForStorefront home, rails and search show only products with a real photo
 * @param requirePhotoForMatching       a page without a real photo is not offered as a match to sellers
 * @param allowPlaceholders             a stand-in image counts as a real photo
 */
public record PictureKnobs(int minImages, int minLongSidePx, int minShortSidePx, int maxMegabytes,
                           boolean checkBackground, boolean checkAspect, boolean checkTextOverlay,
                           boolean requireRealPhotoForStorefront, boolean requirePhotoForMatching,
                           boolean allowPlaceholders) {

    public static final int MAX_IMAGES_PER_LISTING = 10;
    public static final int CEILING_MEGABYTES = 20;
    public static final int CEILING_SIDE_PX = 10_000;

    /** Knob keys in display order. */
    public static final List<String> NAMES = List.of("minImages", "minLongSidePx", "minShortSidePx", "maxMegabytes",
            "checkBackground", "checkAspect", "checkTextOverlay", "requireRealPhotoForStorefront",
            "requirePhotoForMatching", "allowPlaceholders");

    /** The default knob values of a profile. */
    public static PictureKnobs of(PictureProfile profile) {
        return switch (profile) {
            case STRICT -> new PictureKnobs(1, 1000, 500, 8, true, true, true, true, true, false);
            case STARTUP -> new PictureKnobs(1, 500, 300, 8, false, false, false, true, true, false);
            case TESTING -> new PictureKnobs(0, 1, 1, 8, false, false, false, false, false, true);
        };
    }

    /** What is wrong with these values, empty when they can be used. */
    public Map<String, String> problems() {
        Map<String, String> out = new LinkedHashMap<>();
        if (minImages < 0 || minImages > MAX_IMAGES_PER_LISTING) {
            out.put("minImages", "Use 0 to " + MAX_IMAGES_PER_LISTING);
        }
        if (minLongSidePx < 1 || minLongSidePx > CEILING_SIDE_PX) {
            out.put("minLongSidePx", "Use 1 to " + CEILING_SIDE_PX);
        }
        if (minShortSidePx < 1 || minShortSidePx > minLongSidePx) {
            out.put("minShortSidePx", "Use 1 up to the longest-side value");
        }
        if (maxMegabytes < 1 || maxMegabytes > CEILING_MEGABYTES) {
            out.put("maxMegabytes", "Use 1 to " + CEILING_MEGABYTES);
        }
        return out;
    }

    public long maxBytes() {
        return (long) maxMegabytes << 20;
    }
}
