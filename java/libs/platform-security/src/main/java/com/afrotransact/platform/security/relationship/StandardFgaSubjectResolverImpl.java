package com.afrotransact.platform.security.relationship;

import com.afrotransact.platform.security.Actor;

/** {@code client:<sub>} for SERVICE, PARTNER_APP and AI_AGENT principals; {@code user:<sub>} for everyone else. */
class StandardFgaSubjectResolverImpl implements FgaSubjectResolver {

    @Override
    public String subjectOf(Actor actor) {
        String prefix = switch (actor.type()) {
            case SERVICE, PARTNER_APP, AI_AGENT -> "client:";
            case CUSTOMER, SELLER_MEMBER, PROVIDER_MEMBER, STAFF, DRIVER -> "user:";
        };
        return prefix + actor.subject();
    }
}
