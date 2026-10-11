package com.afrotransact.identity.adapter.in.web;

import java.time.Instant;

import com.afrotransact.identity.application.OrgTeamService;
import com.afrotransact.identity.application.SavePreferencesCommand;
import com.afrotransact.identity.application.UserPreferencesView;
import com.afrotransact.identity.application.UserSettingsService;
import com.afrotransact.identity.application.WorkProfileView;
import com.afrotransact.identity.domain.UserSettings.DateFormat;
import com.afrotransact.identity.domain.UserSettings.NumberFormat;
import com.afrotransact.identity.domain.UserSettings.TableDensity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's display preferences, work profile and leaving an organisation (account settings pages). */
@Tag(name = "Me", description = "The signed-in user: profile, roles, permissions and role-based landing")
@RestController
class AccountSettingsController {

    private final UserSettingsService settings;
    private final OrgTeamService team;

    AccountSettingsController(UserSettingsService settings, OrgTeamService team) {
        this.settings = settings;
        this.team = team;
    }

    @Schema(name = "UserPreferences")
    record PreferencesResponse(String locale, String timeZone, DateFormat dateFormat, NumberFormat numberFormat,
                               TableDensity tableDensity, Instant updatedAt) {

        static PreferencesResponse of(UserPreferencesView v) {
            return new PreferencesResponse(v.locale(), v.timeZone(), v.dateFormat(), v.numberFormat(),
                    v.tableDensity(), v.updatedAt());
        }
    }

    @Schema(name = "UserPreferencesRequest")
    record PreferencesRequest(@Size(max = 35) @Schema(examples = "fr-CA") String locale,
                              @Size(max = 64) @Schema(examples = "America/Chicago") String timeZone,
                              @NotNull DateFormat dateFormat, @NotNull NumberFormat numberFormat,
                              @NotNull TableDensity tableDensity) {
    }

    @Schema(name = "WorkProfile")
    record WorkProfileResponse(String jobTitle, Instant updatedAt) {

        static WorkProfileResponse of(WorkProfileView v) {
            return new WorkProfileResponse(v.jobTitle(), v.updatedAt());
        }
    }

    @Schema(name = "WorkProfileRequest")
    record WorkProfileRequest(@Size(max = 80) String jobTitle) {
    }

    @Operation(summary = "Display preferences (language, time zone, date and number format, table density)",
            description = "Language and time zone are the same values notifications-service uses for messages and "
                    + "quiet hours.")
    @GetMapping("/v1/me/preferences")
    PreferencesResponse preferences() {
        return PreferencesResponse.of(settings.preferences());
    }

    @Operation(summary = "Replace the display preferences",
            description = "422 identity.invalid-preferences with field errors for an unknown language tag or time "
                    + "zone. Notification switches are kept.")
    @PutMapping("/v1/me/preferences")
    PreferencesResponse savePreferences(@Valid @RequestBody PreferencesRequest body) {
        return PreferencesResponse.of(settings.savePreferences(new SavePreferencesCommand(body.locale(),
                body.timeZone(), body.dateFormat(), body.numberFormat(), body.tableDensity())));
    }

    @Operation(summary = "Work profile shown in the back offices (job title)")
    @GetMapping("/v1/me/work-profile")
    WorkProfileResponse workProfile() {
        return WorkProfileResponse.of(settings.workProfile());
    }

    @Operation(summary = "Replace the work profile")
    @PutMapping("/v1/me/work-profile")
    WorkProfileResponse saveWorkProfile(@Valid @RequestBody WorkProfileRequest body) {
        return WorkProfileResponse.of(settings.saveWorkProfile(body.jobTitle()));
    }

    @Operation(summary = "Leave an organisation (members who are not its owner)",
            description = "409 identity.owner-cannot-leave for owners (transfer ownership first); 404 "
                    + "identity.member-not-found when not a member. Publishes identity.role.revoked; an organisation "
                    + "the user acts for stops being active.")
    @PostMapping("/v1/me/orgs/{orgId}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void leaveOrg(@PathVariable String orgId) {
        team.leave(orgId);
    }
}
