package com.afrotransact.deliveryruns.domain;

import java.time.Instant;

/**
 * Consent record of one channel.
 *
 * @param channel     the channel
 * @param consentedAt when consent was (last) given
 * @param source      where it was given, e.g. {@code location-page}, {@code checkout}, {@code whatsapp-community}
 * @param withdrawnAt when it was withdrawn (null = in force)
 */
public record ChannelConsent(Channel channel, Instant consentedAt, String source, Instant withdrawnAt) {

    public boolean granted() {
        return withdrawnAt == null;
    }

    ChannelConsent withdraw(Instant now) {
        return granted() ? new ChannelConsent(channel, consentedAt, source, now) : this;
    }
}
