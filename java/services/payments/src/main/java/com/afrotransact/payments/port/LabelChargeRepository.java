package com.afrotransact.payments.port;

import java.util.List;
import java.util.Optional;

import com.afrotransact.payments.domain.LabelCharge;

/** Shipping labels charged to sellers (S256), one row per carrier-gateway label id. */
public interface LabelChargeRepository {

    /** false when the label already has a row (business-key de-duplication). */
    boolean insert(LabelCharge charge);

    /** SELECT … FOR UPDATE by carrier-gateway label id. */
    Optional<LabelCharge> lockByLabel(String labelId);

    /** Optimistic: the version must match; returns the charge with version + 1. */
    LabelCharge update(LabelCharge charge);

    /** The organisation's charged and credited labels, newest first. */
    List<LabelCharge> ofOrg(String orgId, int limit);
}
