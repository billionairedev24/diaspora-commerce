package com.afrotransact.payments.domain;

import java.util.Set;

/**
 * Who is asking, as far as payout rules care.
 *
 * @param staff AfroTransact staff (Finance) acting for an organisation
 */
public record Requester(String subject, Set<String> roles, boolean staff) {

    public Requester {
        roles = Set.copyOf(roles);
    }
}
