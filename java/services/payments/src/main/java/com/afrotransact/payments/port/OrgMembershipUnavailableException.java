package com.afrotransact.payments.port;

/** The seller service could not confirm a membership. The action is refused (HTTP 503), never allowed. */
public class OrgMembershipUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OrgMembershipUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
