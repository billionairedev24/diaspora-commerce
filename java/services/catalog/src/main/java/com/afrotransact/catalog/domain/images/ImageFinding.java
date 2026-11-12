package com.afrotransact.catalog.domain.images;

import java.util.Objects;

/**
 * One finding of the image rules on a picture, as media-service reports it: a code the seller's language is picked
 * by, its level and a plain English message (no ids, no jargon).
 *
 * @param code    for example {@code MAIN_TOO_SMALL}, {@code NOT_WHITE} or {@code HELD_TEXT}
 * @param level   {@code INFO}, {@code WARN}, {@code HOLD} or {@code REJECT}
 * @param message plain English text for the seller
 */
public record ImageFinding(String code, String level, String message) {

    public static final String WARN = "WARN";
    public static final String HOLD = "HOLD";
    public static final String REJECT = "REJECT";
    /** The picture is not one media-service knows, so it could not be checked. */
    public static final String NOT_VERIFIED = "NOT_VERIFIED";

    public ImageFinding {
        Objects.requireNonNull(code, "code");
        level = level == null ? "INFO" : level;
        message = message == null ? "" : message;
    }

    /** A finding that stops the picture being used: a rejection or a hold. */
    public boolean blocks() {
        return REJECT.equals(level) || HOLD.equals(level);
    }
}
