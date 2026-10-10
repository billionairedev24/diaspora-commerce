package com.afrotransact.bookings.domain;

/** The booking is not in a state that allows the action (HTTP 409), e.g. confirming a cancelled booking. */
public class BookingsStateConflict extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public BookingsStateConflict(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
