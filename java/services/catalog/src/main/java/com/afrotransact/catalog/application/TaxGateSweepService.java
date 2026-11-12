package com.afrotransact.catalog.application;

/**
 * The safety net behind the tax publish gate. Every transition already goes through {@link ProductLiveGate}; this sweep
 * catches anything that still slipped through (data written by a tool, an old row) and finishes what a crash left half done.
 */
public interface TaxGateSweepService {

    /**
     * Closes every live listing that has a variant without a resolved tax decision, brings back the listings held for tax
     * that are now fully resolved, and publishes the closing of listings the migration closed.
     *
     * @return how many listings it closed, reopened or announced
     */
    int sweep();
}
