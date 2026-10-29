package com.afrotransact.seller.application;

import java.util.Optional;

import com.afrotransact.seller.domain.BusinessSection;

/**
 * The plain public page every business gets. AfroTransact gives Stripe its address as the business website, so a
 * business never stalls in verification for lack of one and never has to build or host a site.
 */
public interface BusinessPageService {

    /** The address to give Stripe as the business website: its plain page on AfroTransact (made now when needed). */
    String websiteFor(String orgId, BusinessSection business);

    /** The page at {@code slug}; empty when there is none. */
    Optional<PublicBusinessPage> find(String slug);
}
