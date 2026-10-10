package com.afrotransact.identity.domain;

/**
 * A sign-in step was rejected (HTTP 401): wrong email or password, wrong or expired code, unknown challenge, rejected
 * passkey. The message never says whether the account exists.
 */
public class SignInFailed extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final Integer attemptsLeft;

    public SignInFailed(String code, String message) {
        this(code, message, null);
    }

    public SignInFailed(String code, String message, Integer attemptsLeft) {
        super(message);
        this.code = code;
        this.attemptsLeft = attemptsLeft;
    }

    public String code() {
        return code;
    }

    /** Attempts left on the current challenge, when it has a limit. */
    public Integer attemptsLeft() {
        return attemptsLeft;
    }
}
