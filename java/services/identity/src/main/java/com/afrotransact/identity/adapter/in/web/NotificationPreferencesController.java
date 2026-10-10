package com.afrotransact.identity.adapter.in.web;

import java.time.Instant;

import com.afrotransact.identity.application.NotificationPreferencesService;
import com.afrotransact.identity.application.UpdateNotificationPreferencesCommand;
import com.afrotransact.identity.domain.NotificationPreferences;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Notification preferences", description = "Account-level email, SMS and push switches and marketing "
        + "opt-in, read by notifications-service (security messages such as one-time codes always go out)")
@RestController
class NotificationPreferencesController {

    private final NotificationPreferencesService preferences;

    NotificationPreferencesController(NotificationPreferencesService preferences) {
        this.preferences = preferences;
    }

    @Schema(name = "NotificationPreferencesRequest")
    record PreferencesRequest(@NotNull Boolean email, @NotNull Boolean sms, @NotNull Boolean push,
                              @NotNull Boolean marketingOptIn,
                              @Size(max = 35) @Schema(examples = "fr-CA") String locale,
                              @Size(max = 64) @Schema(examples = "America/Chicago") String timeZone) {
    }

    @Schema(name = "NotificationPreferences")
    record PreferencesResponse(boolean email, boolean sms, boolean push, boolean marketingOptIn,
                               Instant marketingOptInAt, String locale, String timeZone, Instant updatedAt) {

        static PreferencesResponse of(NotificationPreferences p) {
            return new PreferencesResponse(p.email(), p.sms(), p.push(), p.marketingOptIn(), p.marketingOptInAt(),
                    p.locale(), p.timeZone(), p.updatedAt());
        }
    }

    @Operation(summary = "Notification switches (defaults: email, SMS and push on; marketing off)")
    @GetMapping("/v1/me/notification-preferences")
    PreferencesResponse get() {
        return PreferencesResponse.of(preferences.get());
    }

    @Operation(summary = "Replace the notification switches")
    @PutMapping("/v1/me/notification-preferences")
    PreferencesResponse put(@Valid @RequestBody PreferencesRequest body) {
        return PreferencesResponse.of(preferences.update(new UpdateNotificationPreferencesCommand(body.email(),
                body.sms(), body.push(), body.marketingOptIn(), body.locale(), body.timeZone())));
    }
}
