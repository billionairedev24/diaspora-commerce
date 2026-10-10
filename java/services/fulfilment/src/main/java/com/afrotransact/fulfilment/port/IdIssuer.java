package com.afrotransact.fulfilment.port;

/** Issues human-friendly identifiers. */
public interface IdIssuer {

    /** A new package label, {@code ATS} + 9 base-36 characters. */
    String packageLabel();

    /** A new route id, {@code RT-} + 8 base-36 characters. */
    String routeId();
}
