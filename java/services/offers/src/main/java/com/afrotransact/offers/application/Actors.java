package com.afrotransact.offers.application;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;

/** Small helpers about the calling actor. */
final class Actors {

    private Actors() {
    }

    static boolean isStaff(Actor actor) {
        return actor.type() == PrincipalType.STAFF || actor.type() == PrincipalType.SERVICE;
    }
}
