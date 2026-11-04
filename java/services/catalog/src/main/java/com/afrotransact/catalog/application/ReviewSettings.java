package com.afrotransact.catalog.application;

import java.time.Duration;
import java.util.Set;

/**
 * Review rules that are configuration, not domain.
 *
 * @param editWindow how long after writing a customer can edit their review (30 days)
 * @param photoHosts hosts review photos may come from (lower case); empty = any https host
 */
public record ReviewSettings(Duration editWindow, Set<String> photoHosts) {

    public ReviewSettings {
        photoHosts = Set.copyOf(photoHosts);
    }

    /** True when the host may serve review photos. */
    public boolean photoHostAllowed(String host) {
        return photoHosts.isEmpty() || (host != null && photoHosts.contains(host));
    }
}
