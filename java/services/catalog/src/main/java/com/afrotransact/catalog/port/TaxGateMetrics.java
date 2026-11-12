package com.afrotransact.catalog.port;

/** Metrics of the tax publish gate (Prometheus: {@code catalog_live_without_tax_decision}, {@code catalog_tax_gate_*}). */
public interface TaxGateMetrics {

    /** A live listing was closed because a variant has no resolved tax decision. */
    void closed(String reasonCode);

    /** A listing held for tax went back on sale after its decision was approved. */
    void restored();
}
