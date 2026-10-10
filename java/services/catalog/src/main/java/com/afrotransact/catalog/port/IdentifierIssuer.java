package com.afrotransact.catalog.port;

import com.afrotransact.catalog.domain.Atin;

/** Issues ATINs and human references; backed by database sequences so ids never collide across pods. */
public interface IdentifierIssuer {

    Atin nextAtin();

    /** Category request reference, e.g. {@code REQ-004213}. */
    String nextCategoryRequestReference();
}
