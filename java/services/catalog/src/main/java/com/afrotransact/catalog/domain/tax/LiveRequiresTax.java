package com.afrotransact.catalog.domain.tax;

import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;

/**
 * The invariant behind the owner's rule: a product is never LIVE (purchasable) unless every one of its variants has a
 * stored, resolved tax decision (docs/engineering/tax-gate-paths.md). The persistence adapter checks it on every write,
 * so a code path that forgets the gate fails closed instead of putting an untaxed item on sale.
 */
public final class LiveRequiresTax {

    private LiveRequiresTax() {
    }

    /** True when the product is not live, or is live with every variant resolved. */
    public static boolean holds(Product product) {
        return product.status() != ProductStatus.LIVE || TaxReadiness.of(product, null, null).ready();
    }

    /** @throws TaxNotReady when the product is live but a variant has no resolved decision (plain words per variant) */
    public static void require(Product product) {
        if (!holds(product)) {
            TaxReadiness readiness = TaxReadiness.of(product, null, null);
            throw new TaxNotReady("This product cannot be live: its tax details are not complete", readiness.blockers(),
                    readiness);
        }
    }

    /** The reason code recorded when a listing is closed for tax: TAX_ plus its most pressing status. */
    public static String reasonCode(Product product) {
        TaxReadiness readiness = TaxReadiness.of(product, null, null);
        return "TAX_" + (readiness.ready() ? "NOT_READY" : readiness.status().name());
    }
}
