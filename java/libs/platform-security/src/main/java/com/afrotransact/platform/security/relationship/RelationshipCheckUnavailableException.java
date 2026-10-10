package com.afrotransact.platform.security.relationship;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The relationship store could not answer; checks fail closed with HTTP 503 (RFC 9457 problem details). */
public class RelationshipCheckUnavailableException extends ErrorResponseException {

    private static final long serialVersionUID = 1L;

    public RelationshipCheckUnavailableException(String message, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, problem(message), cause);
    }

    private static ProblemDetail problem(String message) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, message);
        pd.setTitle("Authorisation temporarily unavailable");
        pd.setProperty("code", "auth.relationship-unavailable");
        return pd;
    }
}
