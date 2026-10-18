package com.afrotransact.seller.port;

import java.util.Optional;

import com.afrotransact.seller.domain.BusinessPage;

/** The public pages of businesses without a website. */
public interface BusinessPageRepository {

    Optional<BusinessPage> findByOrg(String orgId);

    Optional<BusinessPage> findBySlug(String slug);

    /** Inserts the page; false when the slug is taken (the caller tries another). */
    boolean insert(BusinessPage page);
}
