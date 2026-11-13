package com.afrotransact.catalog.port;

/** Metrics of the tax publish gate (Prometheus: {@code catalog_live_without_tax_decision}, {@code catalog_tax_gate_*}, {@code catalog_tax_gate_disagreement_total}). */
public interface TaxGateMetrics {

    /** A live listing was closed because a variant has no resolved tax decision. */
    void closed(String reasonCode);

    /** A listing held for tax went back on sale after its decision was approved. */
    void restored();

    /**
     * Catalog's own copy said a listing was fine, but tax-returns' stored decision is not RESOLVED for a variant: the two
     * sources disagree and the listing was closed (alert {@code CatalogTaxReturnsDisagreement}).
     */
    void disagreement(String reasonCode);

    /** tax-returns could not be asked (timeout, 5xx): states were kept, nobody was marked OK (alert on a rising count). */
    void authorityUnreachable();

    /**
     * Media could not be asked about a held photo while repairing tax state: the photo part was skipped and the listing
     * kept held ({@code catalog_tax_gate_media_unavailable_total}, no labels). Tax enforcement never waits for media.
     */
    void mediaUnavailable();

    /** tax-returns answered a check: stamps {@code catalog_tax_gate_authority_last_check_timestamp_seconds}. */
    void authorityChecked();
}
