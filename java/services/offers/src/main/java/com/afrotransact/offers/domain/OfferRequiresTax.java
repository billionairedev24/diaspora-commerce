package com.afrotransact.offers.domain;

/**
 * The write guard of the tax rule for offers (S384, docs/engineering/tax-gate-paths.md): an offer is never stored ACTIVE
 * unless it carries the basis that says why it may be live as far as tax goes. A new offer therefore gets one only from a
 * stored decision ({@link Offer#TAX_BASIS_SELLER_DECISION}) or because it is the page owner's ({@link
 * Offer#TAX_BASIS_OWNER_PAGE}, covered by the page's own gate); anything else is stored TAX_HELD.
 */
public final class OfferRequiresTax {

    private OfferRequiresTax() {
    }

    /** True when the offer may be stored: not ACTIVE, or ACTIVE with a tax basis. */
    public static boolean holds(Offer o) {
        return o.status() != OfferStatus.ACTIVE || o.taxBasis() != null;
    }

    /**
     * @throws TaxBasisMissing when an ACTIVE offer has no tax basis
     */
    public static void require(Offer o) {
        if (!holds(o)) {
            throw new TaxBasisMissing("Offer " + o.id() + " on " + o.atin() + " cannot be stored as active: the seller has "
                    + "no resolved tax decision on this product");
        }
    }

    /** An active offer without a tax basis was about to be stored; nothing is live without a stored tax decision. */
    public static class TaxBasisMissing extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        public TaxBasisMissing(String message) {
            super(message);
        }
    }
}
