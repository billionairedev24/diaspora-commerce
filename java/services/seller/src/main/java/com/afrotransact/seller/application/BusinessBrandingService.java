package com.afrotransact.seller.application;

import com.afrotransact.seller.domain.BusinessSection;

/** How the business's plain public page looks (description, logo, brand colour), changeable at any time. */
public interface BusinessBrandingService {

    /** The page as shown now; any member of the organisation can read it. */
    PublicBusinessPage get(String orgId);

    /** Replaces the description, logo and brand colour (blank values clear them); owner or admin only. */
    PublicBusinessPage update(String orgId, BusinessSection.Branding branding);
}
