package com.afrotransact.identity.adapter.in.web;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.afrotransact.identity.application.AuthSettingsService.AuthSettingsView;
import com.afrotransact.identity.application.OAuthClientAdminService.ClientDraft;
import com.afrotransact.identity.application.OAuthClientAdminService.ClientWithSecret;
import com.afrotransact.identity.application.PageView;
import com.afrotransact.identity.application.SessionAdminService.Revoked;
import com.afrotransact.identity.application.StepUpService.StepUpProof;
import com.afrotransact.identity.application.TokenInspectorService.Documents;
import com.afrotransact.identity.domain.AdminSession;
import com.afrotransact.identity.domain.AuthSettings;
import com.afrotransact.identity.domain.AuthSettingsOverrides;
import com.afrotransact.identity.domain.OAuthClient;
import com.afrotransact.identity.domain.SigningKey;
import com.afrotransact.identity.domain.TokenInspection;
import io.swagger.v3.oas.annotations.media.Schema;

/** Identity admin console bodies (S291). Secrets and tokens only ever appear in the one response that must show them. */
final class IdentityAdminDtos {

    private static final Duration DEFAULT_ACCESS = Duration.ofMinutes(5);
    private static final Duration DEFAULT_REFRESH = Duration.ofDays(30);

    private IdentityAdminDtos() {
    }

    @Schema(name = "ReasonBody", description = "Why: 10 to 500 characters, kept in the access log")
    record ReasonBody(String reason) {
    }

    @Schema(name = "StepUpRequest", description = "The signed-in staff member's own password, to confirm a dangerous "
            + "action. It is checked and never stored or logged.")
    record StepUpRequest(@Schema(format = "password") String password) {

        /** Never prints the password. */
        @Override
        public String toString() {
            return "StepUpRequest[password=<hidden>]";
        }
    }

    @Schema(name = "StepUpProof", description = "Send the proof as the X-Step-Up header on the dangerous actions "
            + "within five minutes. Shown once; only its hash is stored.")
    record StepUpResponse(String proof, Instant expiresAt) {

        static StepUpResponse of(StepUpProof p) {
            return new StepUpResponse(p.proof(), p.expiresAt());
        }

        /** Never prints the proof. */
        @Override
        public String toString() {
            return "StepUpResponse[proof=<hidden>, expiresAt=" + expiresAt + "]";
        }
    }

    @Schema(name = "OAuthClientRequest")
    record ClientRequest(String clientId, String type, String name, List<String> grantTypes, List<String> scopes,
                         List<String> redirectUris, List<String> postLogoutRedirectUris, Boolean requireConsent,
                         Boolean publicRefreshTokens, Long accessTokenTtlMinutes, Long refreshTokenTtlMinutes,
                         String reason) {

        ClientDraft draft() {
            return new ClientDraft(name, orEmpty(grantTypes), orEmpty(scopes), orEmpty(redirectUris),
                    orEmpty(postLogoutRedirectUris), Boolean.TRUE.equals(requireConsent),
                    Boolean.TRUE.equals(publicRefreshTokens),
                    accessTokenTtlMinutes == null ? DEFAULT_ACCESS : Duration.ofMinutes(accessTokenTtlMinutes),
                    refreshTokenTtlMinutes == null ? DEFAULT_REFRESH : Duration.ofMinutes(refreshTokenTtlMinutes));
        }

        boolean confidential() {
            return "confidential".equalsIgnoreCase(type);
        }

        private static List<String> orEmpty(List<String> l) {
            return l == null ? List.of() : l.stream().filter(v -> v != null && !v.isBlank()).map(String::trim)
                    .distinct().toList();
        }
    }

    @Schema(name = "OAuthClient")
    record ClientResponse(String clientId, String name, String type, List<String> grantTypes, List<String> scopes,
                          List<String> redirectUris, List<String> postLogoutRedirectUris, boolean requirePkce,
                          boolean requireConsent, boolean publicRefreshTokens, long accessTokenTtlMinutes,
                          long refreshTokenTtlMinutes, boolean enabled, boolean platformClient, Instant issuedAt,
                          Instant secretRotatedAt,
                          @Schema(description = "While set and in the future, the previous secret still works")
                          Instant previousSecretExpiresAt) {

        static ClientResponse of(OAuthClient c, boolean platformClient, Instant now) {
            Instant grace = c.previousSecretExpiresAt() != null && c.previousSecretExpiresAt().isAfter(now)
                    ? c.previousSecretExpiresAt() : null;
            return new ClientResponse(c.clientId(), c.name(), c.type(), c.grantTypes(), c.scopes(), c.redirectUris(),
                    c.postLogoutRedirectUris(), c.requirePkce(), c.requireConsent(), c.publicRefreshTokens(),
                    c.accessTokenTtl().toMinutes(), c.refreshTokenTtl().toMinutes(), c.enabled(), platformClient,
                    c.issuedAt(), c.secretRotatedAt(), grace);
        }
    }

    @Schema(name = "OAuthClientSecret", description = "The new client and its secret. The secret is shown here once "
            + "and cannot be read again; it is stored only as a hash.")
    record ClientSecretResponse(ClientResponse client, String secret) {

        static ClientSecretResponse of(ClientWithSecret c, boolean platformClient, Instant now) {
            return new ClientSecretResponse(ClientResponse.of(c.client(), platformClient, now), c.secret());
        }

        /** Never prints the secret. */
        @Override
        public String toString() {
            return "ClientSecretResponse[client=" + client.clientId() + ", secret=<hidden>]";
        }
    }

    @Schema(name = "AdminSession")
    record SessionResponse(String id, String userId, String userName, String userEmail, String clientId,
                           String clientName, String ipAddress, Instant signedInAt, Instant lastUsedAt,
                           Instant expiresAt, boolean refreshable) {

        static SessionResponse of(AdminSession s) {
            return new SessionResponse(s.id(), s.userId(), s.userName(), s.userEmail(), s.clientId(), s.clientName(),
                    s.ipAddress(), s.signedInAt(), s.lastUsedAt(), s.expiresAt(), s.refreshable());
        }
    }

    @Schema(name = "AdminSessionPage")
    record SessionPageResponse(List<SessionResponse> items, int page, int size, long total) {

        static SessionPageResponse of(PageView<AdminSession> p) {
            return new SessionPageResponse(p.items().stream().map(SessionResponse::of).toList(), p.page(), p.size(),
                    p.total());
        }
    }

    @Schema(name = "SessionsRevoked")
    record RevokedResponse(int sessions, int browserSessions) {

        static RevokedResponse of(Revoked r) {
            return new RevokedResponse(r.sessions(), r.browserSessions());
        }
    }

    @Schema(name = "SigningKey")
    record SigningKeyResponse(String kid, String algorithm, String status, Instant createdAt, Instant rotatedAt,
                              Instant retireAfter, Instant retiredAt) {

        static SigningKeyResponse of(SigningKey k, Instant now) {
            return new SigningKeyResponse(k.kid(), k.algorithm(), k.statusAt(now).name(), k.createdAt(),
                    k.rotatedAt(), k.retireAfter(), k.retiredAt());
        }
    }

    @Schema(name = "TokenInspectionRequest")
    record TokenInspectionRequest(String token) {

        /** The token must not reach a log through this record. */
        @Override
        public String toString() {
            return "TokenInspectionRequest[token=<hidden>]";
        }
    }

    @Schema(name = "TokenInspection")
    record TokenInspectionResponse(boolean wellFormed, Map<String, Object> header, Map<String, Object> claims,
                                   List<String> scopes, String keyId, Instant issuedAt, Instant expiresAt,
                                   Boolean expired, boolean signatureValid, boolean issuerValid, boolean valid,
                                   List<String> problems) {

        static TokenInspectionResponse of(TokenInspection t) {
            return new TokenInspectionResponse(t.wellFormed(), t.header(), t.claims(), t.scopes(), t.keyId(),
                    t.issuedAt(), t.expiresAt(), t.expired(), t.signatureValid(), t.issuerValid(), t.valid(),
                    t.problems());
        }
    }

    @Schema(name = "OidcDocuments", description = "The OIDC discovery document and the JWKS (public keys only)")
    record DocumentsResponse(Map<String, Object> discovery, Map<String, Object> jwks) {

        static DocumentsResponse of(Documents d) {
            return new DocumentsResponse(d.discovery(), d.jwks());
        }
    }

    @Schema(name = "AuthSettingsValues")
    record SettingsValues(int passwordMinLength, boolean mfaRequiredAccount, boolean mfaRequiredSeller,
                          boolean mfaRequiredProvider, int sessionIdleTimeoutMinutes,
                          int sessionAbsoluteTimeoutMinutes, Integer accessTokenTtlCapMinutes,
                          Integer refreshTokenTtlCapMinutes) {

        static SettingsValues of(AuthSettings s) {
            return new SettingsValues(s.passwordMinLength(), s.mfaRequiredAccount(), s.mfaRequiredSeller(),
                    s.mfaRequiredProvider(), s.sessionIdleTimeoutMinutes(), s.sessionAbsoluteTimeoutMinutes(),
                    s.accessTokenTtlCapMinutes(), s.refreshTokenTtlCapMinutes());
        }
    }

    @Schema(name = "AuthSettingsOverrides", description = "What is stored; a null field uses the configured default")
    record SettingsOverrides(Integer passwordMinLength, Boolean mfaRequiredAccount, Boolean mfaRequiredSeller,
                             Boolean mfaRequiredProvider, Integer sessionIdleTimeoutMinutes,
                             Integer sessionAbsoluteTimeoutMinutes, Integer accessTokenTtlCapMinutes,
                             Integer refreshTokenTtlCapMinutes) {

        static SettingsOverrides of(AuthSettingsOverrides o) {
            return new SettingsOverrides(o.passwordMinLength(), o.mfaRequiredAccount(), o.mfaRequiredSeller(),
                    o.mfaRequiredProvider(), o.sessionIdleTimeoutMinutes(), o.sessionAbsoluteTimeoutMinutes(),
                    o.accessTokenTtlCapMinutes(), o.refreshTokenTtlCapMinutes());
        }
    }

    @Schema(name = "AuthSettings")
    record SettingsResponse(SettingsValues effective, SettingsOverrides overrides, SettingsValues defaults) {

        static SettingsResponse of(AuthSettingsView v) {
            return new SettingsResponse(SettingsValues.of(v.effective()), SettingsOverrides.of(v.overrides()),
                    SettingsValues.of(v.defaults()));
        }
    }

    @Schema(name = "AuthSettingsUpdate", description = "Replaces the stored overrides: send null to go back to the "
            + "configured default")
    record SettingsUpdate(SettingsOverrides overrides, String reason) {

        AuthSettingsOverrides toDomain() {
            SettingsOverrides o = overrides == null ? new SettingsOverrides(null, null, null, null, null, null, null,
                    null) : overrides;
            return new AuthSettingsOverrides(o.passwordMinLength(), o.mfaRequiredAccount(), o.mfaRequiredSeller(),
                    o.mfaRequiredProvider(), o.sessionIdleTimeoutMinutes(), o.sessionAbsoluteTimeoutMinutes(),
                    o.accessTokenTtlCapMinutes(), o.refreshTokenTtlCapMinutes());
        }
    }
}
