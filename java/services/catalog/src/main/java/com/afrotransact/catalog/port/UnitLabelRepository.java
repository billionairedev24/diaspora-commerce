package com.afrotransact.catalog.port;

import java.time.Instant;
import java.util.Optional;

import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.barcode.UnitLabelCode;

/** AT-FN unit label codes, one per stock owner organisation and variant ATIN. */
public interface UnitLabelRepository {

    Optional<UnitLabelCode> find(String ownerOrg, Atin atin);

    /** Stores {@code code} unless the owner and ATIN already have one; returns the code that is stored. */
    UnitLabelCode insertIfAbsent(UnitLabelCode code, String ownerOrg, Atin atin, Instant now);
}
