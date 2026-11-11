package com.afrotransact.catalog.port;

import java.util.List;

/** Organisations whose listings carry the old "seller is a bakery" answer (seller_is_bakery), until it is migrated. */
public interface BakeryClaimRepository {

    List<String> organisations();
}
