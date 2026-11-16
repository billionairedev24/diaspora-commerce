package com.afrotransact.ai.domain;

/** The optimistic-locking precondition of a cap change failed: missing If-Match (428) or an out-of-date version (412). */
public class SpendCapPreconditionException extends RuntimeException {

    public static final String REQUIRED = "ai.spend-cap.precondition-required";
    public static final String MISMATCH = "ai.spend-cap.version-mismatch";
    private static final long serialVersionUID = 1L;

    private final String code;
    private final int status;

    private SpendCapPreconditionException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public static SpendCapPreconditionException required() {
        return new SpendCapPreconditionException(REQUIRED, 428,
                "Send the version you loaded in If-Match, for example If-Match: \"2\"");
    }

    public static SpendCapPreconditionException mismatch() {
        return new SpendCapPreconditionException(MISMATCH, 412,
                "The cap was changed by someone else since you loaded it; reload and try again");
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }
}
