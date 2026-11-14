package com.afrotransact.taxreturns.adapter.out.tax;

/** An AvaTax call that did not give an answer: refused by AvaTax, not reachable, or too slow. */
final class AvaTaxCallException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** What went wrong, in terms the staff report can show. */
    enum Kind { REFUSED, TIMEOUT, UNREACHABLE }

    private final Kind kind;

    AvaTaxCallException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    Kind kind() {
        return kind;
    }
}
