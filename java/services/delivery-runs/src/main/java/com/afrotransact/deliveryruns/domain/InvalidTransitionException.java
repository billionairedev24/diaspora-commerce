package com.afrotransact.deliveryruns.domain;

/** A lifecycle transition that the current status does not allow (HTTP 409). */
public class InvalidTransitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String from;
    private final String to;

    public InvalidTransitionException(String from, String to) {
        super("Cannot move from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public String from() {
        return from;
    }

    public String to() {
        return to;
    }
}
