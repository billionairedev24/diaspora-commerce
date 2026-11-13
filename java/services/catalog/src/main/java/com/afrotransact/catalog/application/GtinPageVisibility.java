package com.afrotransact.catalog.application;

import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;

/**
 * Who may be told that a GTIN already has a product page, and which ATIN it is. A live page is public, so anyone
 * may be pointed at it ("add your offer to it"). A page that is not live (in review, paused, rejected, taken down,
 * pending an exemption) belongs to its owner and to staff: another seller must not learn that it exists or its ATIN.
 */
final class GtinPageVisibility {

    private GtinPageVisibility() {
    }

    static boolean visibleTo(Product page, Actor actor) {
        if (page.status() == ProductStatus.LIVE) {
            return true;
        }
        if (actor.type() == PrincipalType.STAFF || actor.type() == PrincipalType.SERVICE) {
            return true;
        }
        return actor.orgId().map(org -> org.equals(page.owner().orgId())).orElse(false);
    }
}
