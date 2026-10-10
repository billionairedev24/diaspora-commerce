package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.identity.application.IdentityEventPayloads.NotificationPreferencesUpdated;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.NotificationPreferences;
import com.afrotransact.identity.port.NotificationPreferencesRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class NotificationPreferencesServiceImpl implements NotificationPreferencesService {

    private final CurrentUser current;
    private final NotificationPreferencesRepository preferences;
    private final DomainEventPublisher events;
    private final Clock clock;

    NotificationPreferencesServiceImpl(CurrentUser current, NotificationPreferencesRepository preferences,
                                       DomainEventPublisher events, Clock clock) {
        this.current = current;
        this.preferences = preferences;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationPreferences get() {
        UUID user = current.id();
        return preferences.find(user).orElseGet(() -> NotificationPreferences.defaults(user));
    }

    @Override
    @Transactional
    public NotificationPreferences update(UpdateNotificationPreferencesCommand c) {
        UUID user = current.id();
        Map<String, String> errors = NotificationPreferences.check(c.locale(), c.timeZone());
        if (!errors.isEmpty()) {
            throw new IdentityRuleViolation("identity.invalid-preferences", "Check the highlighted fields", errors);
        }
        NotificationPreferences before = preferences.find(user).orElseGet(() -> NotificationPreferences.defaults(user));
        Instant now = Instant.now(clock);
        Instant optInAt = c.marketingOptIn() ? (before.marketingOptIn() ? before.marketingOptInAt() : now) : null;
        String timeZone = c.timeZone() == null || c.timeZone().isBlank() ? null : c.timeZone().trim();
        NotificationPreferences after = new NotificationPreferences(user, c.email(), c.sms(), c.push(),
                c.marketingOptIn(), optInAt, NotificationPreferences.canonicalLocale(c.locale()), timeZone, now);
        preferences.save(after);
        events.publish(IdentityEvents.NOTIFICATION_PREFERENCES_UPDATED, IdentityEvents.AGGREGATE, user.toString(),
                new NotificationPreferencesUpdated(user.toString(), after.email(), after.sms(), after.push(),
                        after.marketingOptIn(), after.locale(), after.timeZone(), now));
        return after;
    }
}
