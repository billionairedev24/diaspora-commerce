package com.afrotransact.identity.adapter.out.notification;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

import com.afrotransact.identity.application.IdentityEventPayloads.Recipient;
import com.afrotransact.identity.application.IdentityEventPayloads.VerificationRequested;
import com.afrotransact.identity.application.IdentityEvents;
import com.afrotransact.identity.port.VerificationCodeSender;
import com.afrotransact.identity.port.VerificationMessage;
import com.afrotransact.platform.events.DomainEventPublisher;

/**
 * Publishes {@code identity.verification.requested} through the transactional outbox; the notifications service
 * renders and sends the email or SMS. One-time codes are transactional, so SMS quiet hours do not apply.
 */
public class OutboxVerificationCodeSender implements VerificationCodeSender {

    private final DomainEventPublisher events;
    private final Clock clock;

    public OutboxVerificationCodeSender(DomainEventPublisher events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    @Override
    public void send(VerificationMessage m) {
        String channel = m.channel().toLowerCase(Locale.ROOT);
        boolean sms = "sms".equals(channel);
        Recipient recipient = new Recipient(m.userId().toString(), sms ? null : m.destination(),
                sms ? m.destination() : null);
        long minutes = Math.max(1, (Duration.between(clock.instant(), m.expiresAt()).toSeconds() + 59) / 60);
        events.publish(IdentityEvents.VERIFICATION_REQUESTED, IdentityEvents.AGGREGATE, m.userId().toString(),
                new VerificationRequested(recipient, channel, m.code(), minutes, m.expiresAt(), m.purpose().name(),
                        m.userId().toString(), m.firstName(), true));
    }
}
