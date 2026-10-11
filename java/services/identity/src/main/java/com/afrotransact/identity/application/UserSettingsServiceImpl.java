package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.afrotransact.identity.domain.NotificationPreferences;
import com.afrotransact.identity.domain.UserSettings;
import com.afrotransact.identity.port.UserSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class UserSettingsServiceImpl implements UserSettingsService {

    private final CurrentUser current;
    private final UserSettingsRepository settings;
    private final NotificationPreferencesService notifications;
    private final Clock clock;

    UserSettingsServiceImpl(CurrentUser current, UserSettingsRepository settings,
                            NotificationPreferencesService notifications, Clock clock) {
        this.current = current;
        this.settings = settings;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public UserPreferencesView preferences() {
        return view(load(current.id()), notifications.get());
    }

    @Override
    @Transactional
    public UserPreferencesView savePreferences(SavePreferencesCommand c) {
        UUID user = current.id();
        NotificationPreferences before = notifications.get();
        NotificationPreferences after = before;
        if (!Objects.equals(blankToNull(c.locale()), before.locale())
                || !Objects.equals(blankToNull(c.timeZone()), before.timeZone())) {
            // Validates both values (422 identity.invalid-preferences) and tells notifications-service.
            after = notifications.update(new UpdateNotificationPreferencesCommand(before.email(), before.sms(),
                    before.push(), before.marketingOptIn(), c.locale(), c.timeZone()));
        }
        UserSettings saved = load(user).withDisplay(c.dateFormat(), c.numberFormat(), c.tableDensity(),
                Instant.now(clock));
        settings.save(saved);
        return view(saved, after);
    }

    @Override
    @Transactional(readOnly = true)
    public WorkProfileView workProfile() {
        UserSettings s = load(current.id());
        return new WorkProfileView(s.jobTitle(), s.updatedAt());
    }

    @Override
    @Transactional
    public WorkProfileView saveWorkProfile(String jobTitle) {
        UserSettings saved = load(current.id()).withJobTitle(jobTitle, Instant.now(clock));
        settings.save(saved);
        return new WorkProfileView(saved.jobTitle(), saved.updatedAt());
    }

    private UserSettings load(UUID user) {
        return settings.find(user).orElseGet(() -> UserSettings.defaults(user));
    }

    private static UserPreferencesView view(UserSettings s, NotificationPreferences p) {
        Instant updated = s.updatedAt() == null ? p.updatedAt()
                : p.updatedAt() == null || s.updatedAt().isAfter(p.updatedAt()) ? s.updatedAt() : p.updatedAt();
        return new UserPreferencesView(p.locale(), p.timeZone(), s.dateFormat(), s.numberFormat(), s.tableDensity(),
                updated);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
