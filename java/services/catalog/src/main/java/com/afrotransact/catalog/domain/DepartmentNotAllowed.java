package com.afrotransact.catalog.domain;

/**
 * The organisation is not set up to sell in the department of what it is trying to create or change (HTTP 403). The
 * message is written for the seller and says how to add the department.
 */
public class DepartmentNotAllowed extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public DepartmentNotAllowed(String code, String message) {
        super(message);
        this.code = code;
    }

    /** {@code catalog.department-not-allowed}, or {@code catalog.no-departments} when none are recorded. */
    public String code() {
        return code;
    }
}
