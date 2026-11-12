package com.afrotransact.offers.application;

import java.util.Map;
import java.util.UUID;

/**
 * Follows a merge of duplicate catalog pages (S386, {@code catalog.product.merged}): every open offer on a variant of the
 * page that was merged away moves to the mapped variant of the page that survived. A seller who already has the same offer
 * there keeps that one and the moved one is withdrawn. A moved offer is on a different ATIN, so it is held for tax until the
 * seller's decision on the new ATIN is confirmed (S384); it is never live on the strength of the old one.
 */
public interface OfferRelocationService {

    /** Idempotent per event id (inbox). Returns how many offers moved. */
    int pagesMerged(UUID eventId, Merged merged);

    /**
     * Follows staff moving a variant to another page, or splitting it onto a page of its own
     * ({@code catalog.variant.moved}). The variant keeps its ATIN, so every open offer on it stays and only its page changes; an
     * active offer waits as TAX_HELD until the tax release component has confirmed the seller's decision again (the page, and
     * with it the category's tax profile, may differ). Idempotent per event id (inbox). Returns how many offers followed.
     */
    int variantMoved(UUID eventId, VariantMoved moved);

    /**
     * @param variantAtin the moved variant's ATIN (unchanged)
     * @param toLive      whether the page it joined is on sale (its status is LIVE)
     */
    record VariantMoved(String variantAtin, UUID toProductId, String toAtin, UUID toCategoryId, boolean toLive) {
    }

    /**
     * @param variantMap loser variant ATIN to the surviving page's variant ATIN
     * @param survivorCategoryId the surviving page's category (null when not known)
     */
    record Merged(String loserAtin, UUID survivorProductId, String survivorAtin, UUID survivorCategoryId,
                  Map<String, String> variantMap) {

        public Merged {
            variantMap = variantMap == null ? Map.of() : Map.copyOf(variantMap);
        }
    }
}
