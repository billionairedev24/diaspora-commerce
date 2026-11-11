package com.afrotransact.seller.application;

import com.afrotransact.seller.application.SellingViews.SellingCapabilities;
import com.afrotransact.seller.application.SellingViews.SellingApprovals;
import com.afrotransact.seller.domain.SellingFacts;
import com.afrotransact.seller.domain.SellingStatus;

/**
 * "Can this organisation sell this product?" (SELL, APPLY or NOT_AVAILABLE) and the Selling approvals page. Readable
 * by the organisation's members, staff, and catalog-service (the same service scope as the catalog scope).
 */
public interface SellingStatusService {

    /** The answer for one listing's category, brand, product type and material. AfroTransact Retail always sells. */
    SellingStatus status(String orgId, SellingFacts facts);

    /** Every scope that needs approval (or is not available), with this organisation's state for each. */
    SellingApprovals approvals(String orgId);

    /** Seller capabilities such as food service: the product form shows food service fields only when approved. */
    SellingCapabilities capabilities(String orgId);
}
