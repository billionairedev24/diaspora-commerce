package com.afrotransact.catalog.application;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductChangeRequest;

/**
 * Staff decisions that add a variant to a page or move one to another page (S385). They are not content edits (the
 * {@link ChangeRequestApplier} never changes a page's variants) and they follow the tax gate: a variant that joins a page that
 * is on sale can only be attached when staff accept that the page leaves sale until tax review has approved it
 * ({@code holdPageForTax}); the page then goes through {@link ProductLiveGate}, the only way a page leaves or enters sale.
 *
 * <p>A moved variant keeps its ATIN, so the tax decisions already stored for it stay valid; offers on it follow the move
 * ({@code catalog.variant.moved}).
 */
public interface PageVariantService {

    /**
     * NEW_VARIANT: adds the size, colour or other option the request names as a new child ATIN of the page. The tax facts
     * are copied from the page's first variant (an option does not change them) and evaluated again by the tax gate.
     *
     * @param holdPageForTax required when the page is LIVE: the page leaves sale until the new variant has a tax decision
     * @throws com.afrotransact.platform.web.error.BusinessRuleException {@code catalog.change-request-needs-tax-review} for a
     *         LIVE page without {@code holdPageForTax}, {@code catalog.change-request-variant-invalid} for options the page
     *         cannot take
     */
    Added addVariant(ProductChangeRequest request, Product page, boolean holdPageForTax, Instant now);

    /**
     * WRONG_MATCH, MOVE_VARIANT: the variant belongs on another existing page. It keeps its ATIN. The source page keeps at
     * least one variant (otherwise the pages are duplicates: merge them).
     *
     * @param holdTargetForTax required when the target is LIVE and taxed differently from the source, or its tax state is
     *                         not ready for the moved variant
     */
    Moved moveVariant(Product source, String variantAtin, Product target, boolean holdTargetForTax, String by,
                      String reason, UUID changeRequestId, Instant now);

    /**
     * WRONG_MATCH, SPLIT_VARIANT: the variant is a product of its own. It keeps its ATIN and becomes the only variant of a
     * new page (same owner and content as the source) that starts in review and is never live.
     */
    Moved splitVariant(Product source, String variantAtin, String by, String reason, UUID changeRequestId, Instant now);

    /** @param page the stored page; {@code atin} is the new variant's ATIN */
    record Added(Product page, String atin) {
    }

    /** @param variantAtin the ATIN of the moved variant (unchanged by the move) */
    record Moved(Product source, Product target, String variantAtin) {
    }
}
