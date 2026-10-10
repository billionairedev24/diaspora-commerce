package com.afrotransact.identity.application;

import com.afrotransact.identity.domain.NotificationPreferences;

/** The signed-in user's notification channel switches and marketing opt-in. */
public interface NotificationPreferencesService {

    /** Current switches (defaults: every channel on, marketing off). */
    NotificationPreferences get();

    NotificationPreferences update(UpdateNotificationPreferencesCommand command);
}
