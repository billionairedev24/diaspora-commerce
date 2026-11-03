package com.afrotransact.catalog.domain;

/** A review action the review's state does not allow (HTTP 409): editing after the window, replying twice … */
public class ReviewStateConflict extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public ReviewStateConflict(String code, String message) {
        super(message);
        this.code = code;
    }

    /** Stable machine-readable code, e.g. {@code catalog.review-edit-window-closed}. */
    public String code() {
        return code;
    }
}
