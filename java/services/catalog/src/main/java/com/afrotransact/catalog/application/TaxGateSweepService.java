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

    /**
     * The scheduled sweep (also the repair path, see {@code POST /v1/tax-gate/sweep}), forced to run every step now:
     * closes live listings catalog's own copy flags, then asks tax-returns (the authority checkout reads) about EVERY live
     * listing in batches and closes each one that is not RESOLVED for all its variants (hold, plain note,
     * {@code catalog.product.paused}); re-reports the facts of a variant tax-returns has no record of, so it appears in
     * the Tax review queue; reopens held listings that tax-returns now resolves. When tax-returns cannot be reached the
     * state of every listing is kept, {@link Result#authorityReachable()} is false and the unreachable counter rises.
     * Needs {@code tax:classify-admin} when called through {@link #sweepNowAsStaff()}.
     */
    Result sweepNow();

    /** {@link #sweepNow()} for a staff member's request: checks the permission {@code tax:classify-admin} first. */
    Result sweepNowAsStaff();

    /**
     * @param closed             live listings closed (catalog's own flag or tax-returns not RESOLVED)
     * @param closedByTaxReturns of those, closed because tax-returns does not answer RESOLVED
     * @param reopened           listings held for tax that went back on sale
     * @param announced          closings published
     * @param checked            live listings asked about tax-returns
     * @param authorityReachable false when tax-returns could not be asked (nothing was marked OK)
     */
    record Result(int closed, int closedByTaxReturns, int reopened, int announced, int checked,
                  boolean authorityReachable) {

        public int changed() {
            return closed + reopened + announced;
        }
    }
}
