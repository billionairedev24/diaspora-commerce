package com.afrotransact.platform.test.tenant;

/** How a route keeps one tenant away from another's data (docs/engineering/tenant-isolation.md). */
public enum IsolationClass {
    /** No tenant data: anonymous or any signed-in caller sees the same thing. */
    PUBLIC(false),
    /** The row's customer must equal the token subject. */
    CUSTOMER_SELF(true),
    /** A resource fetched by id; the owner org is compared with the caller's org claim (requireForOrg/requireOwned). */
    ORG_OWNED(true),
    /** Lists, exports and reports: the query is filtered by the caller's org (never by a client-supplied one). */
    ORG_SCOPED_LIST(true),
    /** The driver's own assignments only. */
    DRIVER_SELF(true),
    /** Staff only (permission plus principal type); org members are refused. */
    STAFF_ONLY(false),
    /** Service-to-service only (SERVICE principal and scope). */
    SERVICE_ONLY(false),
    /** Possession of a signed, expiring token is the credential (pay links, proof links). */
    TOKEN_LINK(false),
    /** Authenticated by a provider signature, not a user token. */
    WEBHOOK(false),
    /** Not yet proven; must carry a reason. */
    UNVERIFIED(false);

    private final boolean needsTest;

    IsolationClass(boolean needsTest) {
        this.needsTest = needsTest;
    }

    /** True when a named automated cross-tenant test is mandatory in the matrix. */
    public boolean needsTest() {
        return needsTest;
    }

    /** Classes that may be declared once for a whole controller ({@code @Controller} rows). */
    public boolean controllerWide() {
        return this == PUBLIC || this == STAFF_ONLY || this == SERVICE_ONLY || this == WEBHOOK;
    }
}
