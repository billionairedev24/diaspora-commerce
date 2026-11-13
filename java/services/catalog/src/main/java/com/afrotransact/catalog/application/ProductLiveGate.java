package com.afrotransact.catalog.application;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;

/**
 * The single choke point for putting a product on sale. A product is LIVE (purchasable, in search, buyable through its
 * offers) only when every variant has a stored, resolved tax decision. Going live also needs tax-returns' stored decision to be RESOLVED for every
 * variant (the same record checkout reads), asked through {@code TaxDecisionAuthority}; when it cannot be asked the answer is
 * no. Every transition INTO the live status goes
 * through this component: {@code ArchitectureTest} fails the build when any other class calls {@code Product.approve},
 * {@code resume}, {@code restoreFromTakedown} or {@code restoreAfterTaxHold}, and the persistence adapter refuses to
 * store a LIVE product that fails the check. Transitions that take a listing off sale need no gate. See
 * docs/engineering/tax-gate-paths.md.
 */
interface ProductLiveGate {

    /** Staff or auto approval: in review to LIVE. @throws TaxNotReady when a variant has no resolved decision */
    Product approve(Product product, Instant now);

    /** A paused listing resumes. @throws TaxNotReady when a variant has no resolved decision */
    Product resume(Product product, Instant now);

    /**
     * A takedown is reversed. A listing that returns to LIVE without a resolved decision on every variant does not go
     * live: it returns held for tax (in review, with the plain note, a hold and an event).
     */
    Product restoreFromTakedown(Product product, ProductStatus previous, Instant now);

    /**
     * Closes a live listing whose variants are not all resolved (an edit added a variant or changed a tax answer):
     * returns the held product (the caller stores it); any other product is returned unchanged. Records the hold and
     * publishes {@code catalog.product.paused} with the reason code, so search and offers drop it.
     */
    Product closeIfNotReady(Product product, Instant now);

    /**
     * Closes a LIVE listing that tax-returns (the authority checkout reads) does not answer RESOLVED for: IN_REVIEW with
     * the plain note, a hold with {@code reasonCode}, {@code catalog.product.paused} so search, offers and carts drop it.
     * Returns the stored product (unchanged when it is not LIVE any more), or null when it does not exist.
     */
    Product closeForTaxReturns(UUID productId, String reasonCode);

    /**
     * Brings the stored product in line with its variants' tax state: a live product that is not fully resolved is
     * closed; a product held for tax whose variants are now all resolved goes live again (when its seller may still
     * sell it). Returns the stored product, or null when it does not exist.
     */
    Product reconcile(UUID productId);

    /**
     * {@link #reconcile} that also says whether the held-photo check had to be skipped because media could not be asked.
     * Tax is decided first and never depends on media: a listing without a resolved tax decision is closed without asking
     * media. Only after tax passes is a held photo looked up; when media is down the photo part is skipped and the listing
     * stays as it is (a held listing stays held, a photo is never published on an unknown answer).
     */
    Reconciliation reconcileChecked(UUID productId);

    /**
     * @param product           the stored product, or null when it does not exist
     * @param photoCheckSkipped true when a held photo could not be looked up because media was unavailable
     */
    record Reconciliation(Product product, boolean photoCheckSkipped) {
    }

    /** Forgets the tax hold of a product a person decided about (approved or rejected), so it never auto-restores. */
    void releaseHold(UUID productId, Instant now);
}
