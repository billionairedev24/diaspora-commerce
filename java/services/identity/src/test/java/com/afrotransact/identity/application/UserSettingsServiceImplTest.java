package com.afrotransact.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.NotificationPreferences;
import com.afrotransact.identity.domain.UserSettings;
import com.afrotransact.identity.domain.UserSettings.DateFormat;
import com.afrotransact.identity.domain.UserSettings.NumberFormat;
import com.afrotransact.identity.domain.UserSettings.TableDensity;
import com.afrotransact.identity.port.UserSettingsRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class UserSettingsServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    private final UUID id = UUID.randomUUID();
    private final UserSettingsRepository repo = mock(UserSettingsRepository.class);
    private final NotificationPreferencesService notifications = mock(NotificationPreferencesService.class);
    private final UserSettingsServiceImpl service = new UserSettingsServiceImpl(
            new CurrentUser(() -> new Actor(id.toString(), PrincipalType.SELLER_MEMBER, Optional.empty(), Set.of(),
                    Set.of())), repo, notifications, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void defaultsFollowTheLanguageWithComfortableTables() {
        when(repo.find(id)).thenReturn(Optional.empty());
        when(notifications.get()).thenReturn(NotificationPreferences.defaults(id));
        UserPreferencesView v = service.preferences();
        assertThat(v.dateFormat()).isEqualTo(DateFormat.AUTO);
        assertThat(v.numberFormat()).isEqualTo(NumberFormat.AUTO);
        assertThat(v.tableDensity()).isEqualTo(TableDensity.COMFORTABLE);
        assertThat(v.locale()).isNull();
        assertThat(service.workProfile().jobTitle()).isNull();
    }

    @Test
    void savingKeepsTheNotificationSwitchesAndOnlyRewritesLanguageAndZoneWhenTheyChange() {
        NotificationPreferences current = new NotificationPreferences(id, true, false, true, true, NOW, "en-US",
                "America/Chicago", NOW);
        when(repo.find(id)).thenReturn(Optional.empty());
        when(notifications.get()).thenReturn(current);

        UserPreferencesView same = service.savePreferences(new SavePreferencesCommand("en-US", "America/Chicago",
                DateFormat.DMY, NumberFormat.SPACE_COMMA, TableDensity.COMPACT));
        verify(notifications, never()).update(any());
        assertThat(same.tableDensity()).isEqualTo(TableDensity.COMPACT);
        ArgumentCaptor<UserSettings> saved = ArgumentCaptor.forClass(UserSettings.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().dateFormat()).isEqualTo(DateFormat.DMY);
        assertThat(saved.getValue().updatedAt()).isEqualTo(NOW);

        when(notifications.update(any())).thenReturn(new NotificationPreferences(id, true, false, true, true, NOW,
                "fr-CA", "America/Toronto", NOW));
        UserPreferencesView changed = service.savePreferences(new SavePreferencesCommand("fr-ca", "America/Toronto",
                DateFormat.YMD, NumberFormat.DOT_COMMA, TableDensity.COMFORTABLE));
        ArgumentCaptor<UpdateNotificationPreferencesCommand> cmd =
                ArgumentCaptor.forClass(UpdateNotificationPreferencesCommand.class);
        verify(notifications).update(cmd.capture());
        assertThat(cmd.getValue()).isEqualTo(new UpdateNotificationPreferencesCommand(true, false, true, true,
                "fr-ca", "America/Toronto"));
        assertThat(changed.locale()).isEqualTo("fr-CA");
        assertThat(changed.timeZone()).isEqualTo("America/Toronto");
    }

    @Test
    void anInvalidTimeZoneSavesNothing() {
        when(repo.find(id)).thenReturn(Optional.empty());
        when(notifications.get()).thenReturn(NotificationPreferences.defaults(id));
        when(notifications.update(any())).thenThrow(new IdentityRuleViolation("identity.invalid-preferences",
                "Check the highlighted fields", Map.of("timeZone", "Use an IANA time zone")));
        assertThatThrownBy(() -> service.savePreferences(new SavePreferencesCommand(null, "Mars/Olympus",
                DateFormat.AUTO, NumberFormat.AUTO, TableDensity.COMPACT))).isInstanceOf(IdentityRuleViolation.class);
        verify(repo, never()).save(any());
    }

    @Test
    void jobTitlesAreTrimmedAndBlankClearsThem() {
        when(repo.find(id)).thenReturn(Optional.of(UserSettings.defaults(id)));
        assertThat(service.saveWorkProfile("  Head of   Fulfilment ").jobTitle()).isEqualTo("Head of Fulfilment");
        assertThat(service.saveWorkProfile("   ").jobTitle()).isNull();
        assertThat(UserSettings.cleanJobTitle("x".repeat(120))).hasSize(UserSettings.JOB_TITLE_MAX);
    }
}
