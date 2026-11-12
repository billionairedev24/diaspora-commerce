package com.afrotransact.catalog.domain.images;

/**
 * How a listing's main image stands against the rules: the state behind the "Improve your image" task (S392).
 *
 * <ul>
 *   <li>{@link #OK}: it passes;
 *   <li>{@link #IMPROVE}: it passes with a warning (for example the product could fill more of the photo);
 *   <li>{@link #FAIL}: it breaks a rule (too small, not on white, held, or not a photo media knows).
 * </ul>
 *
 * A listing that fails stays live (grandfathered) until the grace period is over and the hide step is switched on.
 */
public enum ImageQuality {
    OK, IMPROVE, FAIL;

    /** The state for the verdict of a main image. */
    public static ImageQuality of(ImageCheckResult result) {
        if (result.blocked()) {
            return FAIL;
        }
        return "WARN".equals(result.verdict()) ? IMPROVE : OK;
    }
}
