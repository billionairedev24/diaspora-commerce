package com.afrotransact.identity.adapter.in.web;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.identity.adapter.in.web.IdentityAdminDtos.ClientRequest;
import com.afrotransact.identity.adapter.in.web.IdentityAdminDtos.ClientResponse;
import com.afrotransact.identity.adapter.in.web.IdentityAdminDtos.ClientSecretResponse;
import com.afrotransact.identity.adapter.in.web.IdentityAdminDtos.DocumentsResponse;
import com.afrotransact.identity.adapter.in.web.IdentityAdminDtos.ReasonBody;
import com.afrotransact.identity.adapter.in.web.IdentityAdminDtos.RevokedResponse;
import com.afrotransact.identity.adapter.in.web.IdentityAdminDtos.SessionPageResponse;
import com.afrotransact.identity.adapter.in.web.IdentityAdminDtos.SessionResponse;
import com.afrotransact.identity.adapter.in.web.IdentityAdminDtos.SettingsResponse;
import com.afrotransact.identity.adapter.in.web.IdentityAdminDtos.SettingsUpdate;
import com.afrotransact.identity.adapter.in.web.IdentityAdminDtos.SigningKeyResponse;
import com.afrotransact.identity.adapter.in.web.IdentityAdminDtos.StepUpRequest;
import com.afrotransact.identity.adapter.in.web.IdentityAdminDtos.StepUpResponse;
import com.afrotransact.identity.adapter.in.web.IdentityAdminDtos.TokenInspectionRequest;
import com.afrotransact.identity.adapter.in.web.IdentityAdminDtos.TokenInspectionResponse;
import com.afrotransact.identity.application.AuthSettingsService;
import com.afrotransact.identity.application.OAuthClientAdminService;
import com.afrotransact.identity.application.ProtectedClients;
import com.afrotransact.identity.application.SessionAdminService;
import com.afrotransact.identity.application.SigningKeyAdminService;
import com.afrotransact.identity.application.StepUpService;
import com.afrotransact.identity.application.TokenInspectorService;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Identity admin console API (S291), all behind {@code identity:admin}. Changes need a reason and are written to the
 * access change log; secrets and tokens never appear in logs or in the access log.
 */
@Tag(name = "Identity admin", description = "OAuth clients, sessions and tokens, signing keys, token inspector and "
        + "authentication settings (identity:admin)")
@RestController
class IdentityAdminController {

    private static final String STEP_UP = "X-Step-Up";
    private static final String STEP_UP_HELP = "Step-up proof from POST /v1/admin/identity/step-up; not needed within "
            + "five minutes of signing in";

    private final OAuthClientAdminService clients;
    private final SessionAdminService sessions;
    private final SigningKeyAdminService signingKeys;
    private final TokenInspectorService inspector;
    private final AuthSettingsService settings;
    private final StepUpService stepUp;
    private final ProtectedClients platformClients;
    private final Clock clock;

    IdentityAdminController(OAuthClientAdminService clients, SessionAdminService sessions,
                            SigningKeyAdminService signingKeys, TokenInspectorService inspector,
                            AuthSettingsService settings, StepUpService stepUp, ProtectedClients platformClients,
                            Clock clock) {
        this.clients = clients;
        this.sessions = sessions;
        this.signingKeys = signingKeys;
        this.inspector = inspector;
        this.settings = settings;
        this.stepUp = stepUp;
        this.platformClients = platformClients;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- step-up

    @Operation(summary = "Confirm your password for the dangerous actions; returns a proof for the X-Step-Up header",
            description = "The proof is valid for five minutes and bound to the signed-in staff member. Wrong passwords "
                    + "count towards the account's sign-in lockout. Signing in again also counts: the dangerous "
                    + "actions accept an access token whose sign-in is less than five minutes old.")
    @PostMapping("/v1/admin/identity/step-up")
    StepUpResponse confirmPassword(@RequestBody StepUpRequest body) {
        return StepUpResponse.of(stepUp.verify(body.password()));
    }

    /** The dangerous actions need a step-up proof or a sign-in from the last five minutes (403 otherwise). */
    private void requireStepUp(String proof, Jwt jwt) {
        stepUp.require(proof, jwt == null ? null : jwt.getClaimAsInstant("auth_time"));
    }

    // ---------------------------------------------------------------- clients

    @Operation(summary = "Every registered OAuth client (identity:admin)")
    @GetMapping("/v1/admin/identity/clients")
    List<ClientResponse> adminClients() {
        Instant now = Instant.now(clock);
        return clients.list().stream().map(c -> ClientResponse.of(c, platformClients.contains(c.clientId()), now))
                .toList();
    }

    @Operation(summary = "One OAuth client (identity:admin)")
    @GetMapping("/v1/admin/identity/clients/{clientId}")
    ClientResponse adminClient(@PathVariable String clientId) {
        return ClientResponse.of(clients.get(clientId), platformClients.contains(clientId), Instant.now(clock));
    }

    @Operation(summary = "Register an OAuth client; a confidential client's secret is in the response, once",
            description = "Needs a reason. Written to the access change log.")
    @PostMapping("/v1/admin/identity/clients")
    @ResponseStatus(HttpStatus.CREATED)
    ClientSecretResponse createAdminClient(@RequestBody ClientRequest body) {
        if (body.clientId() == null || body.clientId().isBlank()) {
            throw new IdentityRuleViolation("identity.invalid-client", "Check the highlighted fields",
                    Map.of("clientId", "Give the client an id"));
        }
        String id = body.clientId().trim();
        return ClientSecretResponse.of(clients.create(body.draft(), id, body.confidential(), body.reason()),
                platformClients.contains(id), Instant.now(clock));
    }

    @Operation(summary = "Edit an OAuth client (everything but its id and type)",
            description = "Needs a reason. Written to the access change log with the before and after values.")
    @PutMapping("/v1/admin/identity/clients/{clientId}")
    ClientResponse updateAdminClient(@PathVariable String clientId, @RequestBody ClientRequest body) {
        return ClientResponse.of(clients.update(clientId, body.draft(), body.reason()),
                platformClients.contains(clientId), Instant.now(clock));
    }

    @Operation(summary = "Disable a client: the token endpoint refuses it and its refresh tokens stop working")
    @PostMapping("/v1/admin/identity/clients/{clientId}/disable")
    ClientResponse disableAdminClient(@PathVariable String clientId, @RequestBody ReasonBody body,
                                      @Parameter(description = STEP_UP_HELP) @RequestHeader(value = STEP_UP, required = false) String proof,
                                      @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        requireStepUp(proof, jwt);
        return ClientResponse.of(clients.setEnabled(clientId, false, body.reason()),
                platformClients.contains(clientId), Instant.now(clock));
    }

    @Operation(summary = "Enable a disabled client")
    @PostMapping("/v1/admin/identity/clients/{clientId}/enable")
    ClientResponse enableAdminClient(@PathVariable String clientId, @RequestBody ReasonBody body) {
        return ClientResponse.of(clients.setEnabled(clientId, true, body.reason()),
                platformClients.contains(clientId), Instant.now(clock));
    }

    @Operation(summary = "Rotate a confidential client's secret; the new secret is in the response, once",
            description = "The previous secret keeps working for 24 hours so deployed callers can switch over.")
    @PostMapping("/v1/admin/identity/clients/{clientId}/rotate-secret")
    ClientSecretResponse rotateAdminClientSecret(@PathVariable String clientId, @RequestBody ReasonBody body) {
        return ClientSecretResponse.of(clients.rotateSecret(clientId, body.reason()),
                platformClients.contains(clientId), Instant.now(clock));
    }

    // ---------------------------------------------------------------- sessions and tokens

    @Operation(summary = "Active sign-ins and refresh tokens, by user or client",
            description = "q matches the user's name or email and the client's id or name.")
    @GetMapping("/v1/admin/identity/sessions")
    SessionPageResponse adminSessions(@RequestParam(required = false) String q,
                                 @RequestParam(required = false) UUID userId,
                                 @RequestParam(required = false) String clientId,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "25") int size) {
        return SessionPageResponse.of(sessions.search(q, userId, clientId, page, size));
    }

    @Operation(summary = "One active sign-in")
    @GetMapping("/v1/admin/identity/sessions/{sessionId}")
    SessionResponse adminSession(@PathVariable String sessionId) {
        return SessionResponse.of(sessions.get(sessionId));
    }

    @Operation(summary = "Revoke one sign-in: its refresh token stops working at once")
    @PostMapping("/v1/admin/identity/sessions/{sessionId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeAdminSession(@PathVariable String sessionId, @RequestBody ReasonBody body) {
        sessions.revoke(sessionId, body.reason());
    }

    @Operation(summary = "Sign a user out everywhere: every sign-in and single sign-on browser session")
    @PostMapping("/v1/admin/identity/users/{userId}/sessions/revoke")
    RevokedResponse revokeAdminUserSessions(@PathVariable UUID userId, @RequestBody ReasonBody body,
                                            @Parameter(description = STEP_UP_HELP) @RequestHeader(value = STEP_UP, required = false) String proof,
                                            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        requireStepUp(proof, jwt);
        return RevokedResponse.of(sessions.revokeAllOfUser(userId, body.reason()));
    }

    @Operation(summary = "Revoke every sign-in of a client")
    @PostMapping("/v1/admin/identity/clients/{clientId}/sessions/revoke")
    RevokedResponse revokeAdminClientSessions(@PathVariable String clientId, @RequestBody ReasonBody body,
                                              @Parameter(description = STEP_UP_HELP) @RequestHeader(value = STEP_UP, required = false) String proof,
                                              @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        requireStepUp(proof, jwt);
        return RevokedResponse.of(sessions.revokeAllOfClient(clientId, body.reason()));
    }

    // ---------------------------------------------------------------- signing keys, inspector, discovery

    @Operation(summary = "The JWKS signing keys: active, retiring and retired")
    @GetMapping("/v1/admin/identity/signing-keys")
    List<SigningKeyResponse> adminSigningKeys() {
        Instant now = Instant.now(clock);
        return signingKeys.list().stream().map(k -> SigningKeyResponse.of(k, now)).toList();
    }

    @Operation(summary = "Rotate the signing key: a new key signs new tokens, the old one stays published until the "
            + "longest access token has expired")
    @PostMapping("/v1/admin/identity/signing-keys/rotate")
    @ResponseStatus(HttpStatus.CREATED)
    SigningKeyResponse rotateAdminSigningKey(@RequestBody ReasonBody body,
                                             @Parameter(description = STEP_UP_HELP) @RequestHeader(value = STEP_UP, required = false) String proof,
                                             @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        requireStepUp(proof, jwt);
        return SigningKeyResponse.of(signingKeys.rotate(body.reason()), Instant.now(clock));
    }

    @Operation(summary = "Decode a JWT and check its signature and issuer; the token is never stored or logged")
    @PostMapping("/v1/admin/identity/token-inspector")
    TokenInspectionResponse inspectToken(@RequestBody TokenInspectionRequest body) {
        return TokenInspectionResponse.of(inspector.inspect(body.token()));
    }

    @Operation(summary = "The OIDC discovery document and the JWKS (public keys only)")
    @GetMapping("/v1/admin/identity/discovery")
    DocumentsResponse adminDiscovery() {
        return DocumentsResponse.of(inspector.documents());
    }

    // ---------------------------------------------------------------- settings

    @Operation(summary = "Authentication settings: in force, stored overrides and configured defaults")
    @GetMapping("/v1/admin/identity/settings")
    SettingsResponse adminAuthSettings() {
        return SettingsResponse.of(settings.view());
    }

    @Operation(summary = "Replace the stored authentication settings",
            description = "Validated, needs a reason, written to the access change log with before and after.")
    @PutMapping("/v1/admin/identity/settings")
    SettingsResponse updateAdminAuthSettings(@RequestBody SettingsUpdate body,
                                             @Parameter(description = STEP_UP_HELP) @RequestHeader(value = STEP_UP, required = false) String proof,
                                             @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        requireStepUp(proof, jwt);
        return SettingsResponse.of(settings.update(body.toDomain(), body.reason()));
    }
}
