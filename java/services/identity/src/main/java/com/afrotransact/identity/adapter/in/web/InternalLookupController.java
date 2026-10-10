package com.afrotransact.identity.adapter.in.web;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.afrotransact.identity.application.ContactLookupService;
import com.afrotransact.identity.application.ContactView;
import com.afrotransact.identity.application.OrgOwnersView;
import com.afrotransact.identity.domain.PushApp;
import com.afrotransact.platform.web.error.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Internal lookups", description = "Service-to-service lookups for notifications-service: SERVICE "
        + "principals (client credentials) with users:read. 404 = unknown user or organisation.")
@RestController
class InternalLookupController {

    private final ContactLookupService lookups;

    InternalLookupController(ContactLookupService lookups) {
        this.lookups = lookups;
    }

    @Schema(name = "PushTarget")
    record PushTargetResponse(String token,
                              @Schema(allowableValues = {"fcm", "apns"}, description = "Push transport") String platform,
                              @Schema(allowableValues = {"customer", "driver"}) String app,
                              @Schema(allowableValues = {"ios", "android", "web"}) String os) {
    }

    @Schema(name = "ChannelPreferences", description = "false = drop the channel (except security messages)")
    record ChannelPreferencesResponse(boolean email, boolean sms, boolean push) {
    }

    @Schema(name = "UserContact")
    record ContactResponse(String userId, String firstName, String email, boolean emailVerified,
                           @Schema(description = "E.164") String phone,
                           @Schema(description = "false: the number was never confirmed with a code; do not text it")
                           boolean phoneVerified,
                           @Schema(description = "BCP 47, null = default") String locale,
                           @Schema(description = "IANA zone, null = market zone") String timeZone,
                           @Schema(description = "ISO country, e.g. US") String market,
                           List<PushTargetResponse> pushTokens, ChannelPreferencesResponse preferences,
                           boolean marketingOptIn) {

        static ContactResponse of(ContactView c) {
            return new ContactResponse(c.userId(), c.firstName(), c.email(), c.emailVerified(), c.phone(),
                    c.phoneVerified(), c.locale(), c.timeZone(), c.market(),
                    c.pushTokens().stream().map(t -> new PushTargetResponse(t.token(), t.platform(), t.app(), t.os()))
                            .toList(),
                    new ChannelPreferencesResponse(c.preferences().email(), c.preferences().sms(),
                            c.preferences().push()), c.marketingOptIn());
        }
    }

    @Schema(name = "OrgOwners")
    record OrgOwnersResponse(String orgId, List<String> ownerUserIds) {
    }

    @Operation(summary = "How to reach a user: email, verified phone, push tokens, locale, time zone, market, "
            + "channel switches")
    @GetMapping("/v1/internal/users/{userId}/contact")
    ContactResponse contact(@PathVariable String userId,
                            @Parameter(description = "Only push tokens of this app")
                            @RequestParam(required = false) @Schema(allowableValues = {"customer", "driver"})
                            String app) {
        PushApp filter = app == null || app.isBlank() ? null : PushApp.valueOf(app.trim().toUpperCase(Locale.ROOT));
        return ContactResponse.of(lookups.contact(parse(userId), filter));
    }

    @Operation(summary = "Owner accounts of a seller or provider organisation")
    @GetMapping("/v1/internal/orgs/{orgId}/owners")
    OrgOwnersResponse owners(@PathVariable String orgId) {
        OrgOwnersView v = lookups.orgOwners(orgId);
        return new OrgOwnersResponse(v.orgId(), v.ownerUserIds());
    }

    /** Ids that are not account UUIDs cannot exist: 404 (not 400), so callers treat them as unknown recipients. */
    private static UUID parse(String userId) {
        try {
            return UUID.fromString(userId);
        } catch (IllegalArgumentException ex) {
            throw new NotFoundException("identity.account-not-found", "Account not found");
        }
    }
}
