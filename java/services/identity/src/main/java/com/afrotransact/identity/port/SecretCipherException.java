package com.afrotransact.identity.port;

/** A sealed secret could not be opened (unknown key, wrong record, tampered or malformed envelope). */
public class SecretCipherException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SecretCipherException(String message) {
        super(message);
    }

    public SecretCipherException(String message, Throwable cause) {
        super(message, cause);
    }
}
