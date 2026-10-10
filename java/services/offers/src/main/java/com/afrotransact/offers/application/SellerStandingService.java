package com.afrotransact.offers.application;

import java.util.UUID;

import com.afrotransact.offers.domain.SellerStanding;

/** Seller enforcement, display names and performance inputs that feed the buy box. */
public interface SellerStandingService {

    /** seller.listings.hidden / restored: suppresses or restores every offer of the organisation. */
    void listingsVisibility(UUID eventId, String orgId, boolean hidden);

    /** seller.org.approved / updated: display name used as "Sold by". */
    void orgProfile(UUID eventId, String orgId, String displayName);

    /** Staff / service: performance inputs (rating, on-time rate, defect rate) and the restricted flag. */
    SellerStanding updatePerformance(String orgId, SellerPerformanceCommand command);

    /** The organisation's own standing (staff: any organisation). */
    SellerStanding get(String orgId);
}
