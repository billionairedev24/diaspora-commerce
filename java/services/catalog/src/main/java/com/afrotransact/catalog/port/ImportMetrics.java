package com.afrotransact.catalog.port;

/** Counters of bulk uploads (S393 to S396). Tags are the mode and the outcome only: never an organisation, SKU or file. */
public interface ImportMetrics {

    /** An upload was validated and stored; {@code mode} is CREATE, UPDATE, OFFER or PRICE_QTY. */
    void uploaded(String mode);

    /** One row was finished by the job; {@code outcome} is a RowOutcome name. */
    void row(String mode, String outcome);

    /** An upload was refused before anything was stored; {@code reason} is a short fixed word (DAILY_LIMIT, ACTIVE_UPLOAD). */
    void refused(String mode, String reason);
}
