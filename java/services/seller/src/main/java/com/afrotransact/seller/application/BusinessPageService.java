package com.afrotransact.seller.application;

import java.util.Optional;

import com.afrotransact.seller.domain.BusinessSection;

/**
 * The plain public page of a business that has no website of its own. AfroTransact gives Stripe its address as the
 * business website, so a business never stalls in verification for lack of one. A business that has a website gets no
 * page: its own site is given to Stripe instead.
 */
public interface BusinessPageService {

    /**
     * The address to give Stripe as the business website: the business's own website, else the address of its plain
     * page (made now when it does not exist yet).
     */
    String websiteFor(String orgId, BusinessSection business);

    /** The page at {@code slug}; empty when there is none. */
    Optional<PublicBusinessPage> find(String slug);
}
