package com.afrotransact.catalog.port;

import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.barcode.UnitLabelCode;

/** Issues ATINs and human references; backed by database sequences so ids never collide across pods. */
public interface IdentifierIssuer {

    Atin nextAtin();

    /** A fresh AT-FN unit label code ({@code ATF} + 7 base-36 characters). */
    UnitLabelCode nextUnitLabelCode();

    /** Category request reference, e.g. {@code REQ-004213}. */
    String nextCategoryRequestReference();
}
