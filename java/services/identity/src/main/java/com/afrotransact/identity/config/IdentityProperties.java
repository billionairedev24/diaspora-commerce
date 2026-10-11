package com.afrotransact.identity.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code at.identity.*}.
 *
 * @param issuer            OIDC issuer; resource servers fetch {@code <issuer>/oauth2/jwks}
 * @param homeUrl           where a browser goes after signing in without a pending authorization request
 * @param staffEmailDomains email domains reserved for staff (cannot self-register)
 * @param signing           token signing key
 * @param verification      one-time code delivery
 * @param mfa               two-step verification settings
 * @param webauthn          passkey relying party
 * @param corsAllowedOrigins browser origins allowed to call /v1 and the token endpoint
 * @param social            social provider client credentials, keyed by provider id (google, apple, facebook)
 * @param bootstrapAdmin    first super administrator (created when missing; leave email blank to skip)
 * @param clients           OAuth clients seeded at start-up (upserted by client id)
 * @param firstParty        first-party sign-in API (clients allowed to call it and redeem login tickets)
 * @param signInProtection  brute-force protection limits (per account and per IP)
 * @param invitations       organisation invitations (Seller Center accept page)
 * @param dataProtection    encryption at rest of MFA secrets (key-encryption key provider and keys)
 * @param pages             hosted sign-in pages: the apps they brand themselves for and their legal/support links
 */
@ConfigurationProperties(prefix = "at.identity")
public record IdentityProperties(
        String issuer,
        String homeUrl,
        List<String> staffEmailDomains,
        Signing signing,
        Verification verification,
        Mfa mfa,
        WebAuthn webauthn,
        List<String> corsAllowedOrigins,
        Map<String, Social> social,
        BootstrapAdmin bootstrapAdmin,
        List<Client> clients,
        FirstParty firstParty,
        SignInProtection signInProtection,
        Invitations invitations,
        DataProtection dataProtection,
        Pages pages) {

    public IdentityProperties {
        homeUrl = homeUrl == null ? "/" : homeUrl;
        staffEmailDomains = staffEmailDomains == null ? List.of() : List.copyOf(staffEmailDomains);
        signing = signing == null ? new Signing(null, null, false, null) : signing;
        verification = verification == null ? new Verification(null) : verification;
        mfa = mfa == null ? new Mfa(null, null) : mfa;
        webauthn = webauthn == null ? new WebAuthn(null, null, null, null) : webauthn;
        corsAllowedOrigins = corsAllowedOrigins == null ? List.of() : List.copyOf(corsAllowedOrigins);
        social = social == null ? Map.of() : Map.copyOf(social);
        bootstrapAdmin = bootstrapAdmin == null ? new BootstrapAdmin(null, null, null, null, null) : bootstrapAdmin;
        clients = clients == null ? List.of() : List.copyOf(clients);
        firstParty = firstParty == null ? new FirstParty(null) : firstParty;
        signInProtection = signInProtection == null ? new SignInProtection(null, null) : signInProtection;
        invitations = invitations == null ? new Invitations(null) : invitations;
        dataProtection = dataProtection == null ? new DataProtection(null, null, null, null, null) : dataProtection;
        pages = pages == null ? new Pages(null, null, null, null, null, null, null, null) : pages;
    }

    /**
     * Hosted sign-in, sign-up and two-step pages.
     *
     * @param storefrontUrl   storefront origin ({@code PUBLIC_WEB_URL}); shopper branding and the default home link
     * @param sellerCenterUrl Seller Center origin ({@code SELLER_CENTER_URL}): authorization requests returning there
     *                        get the Seller Center branding
     * @param staffConsoleUrl staff console origin ({@code STAFF_CONSOLE_URL}): staff branding, no self sign-up
     * @param supportUrl      help page linked from "Forgot password?" and error pages
     * @param termsUrl        Conditions of Use (sign-up consent)
     * @param privacyUrl      Privacy Notice (sign-up consent)
     * @param smsTermsUrl     SMS terms (text-message consent)
     * @param smsProgram      name of the text-message programme in the SMS consent copy
     */
    public record Pages(String storefrontUrl, String sellerCenterUrl, String staffConsoleUrl, String supportUrl,
                        String termsUrl, String privacyUrl, String smsTermsUrl, String smsProgram) {

        public Pages {
            storefrontUrl = trim(storefrontUrl, "http://localhost:3000");
            sellerCenterUrl = trim(sellerCenterUrl, "http://localhost:3001");
            staffConsoleUrl = trim(staffConsoleUrl, "http://localhost:3002");
            supportUrl = supportUrl == null || supportUrl.isBlank() ? storefrontUrl + "/help" : supportUrl;
            termsUrl = termsUrl == null || termsUrl.isBlank() ? storefrontUrl + "/legal/conditions-of-use" : termsUrl;
            privacyUrl = privacyUrl == null || privacyUrl.isBlank() ? storefrontUrl + "/legal/privacy" : privacyUrl;
            smsTermsUrl = smsTermsUrl == null || smsTermsUrl.isBlank() ? storefrontUrl + "/legal/sms-terms" : smsTermsUrl;
            smsProgram = smsProgram == null || smsProgram.isBlank() ? "AfroTransact Alerts" : smsProgram;
        }

        private static String trim(String url, String fallback) {
            String v = url == null || url.isBlank() ? fallback : url.trim();
            return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
        }
    }

    /**
     * Envelope encryption of MFA secrets at rest.
     *
     * @param provider         {@code local} (default: key ring from the secret store) or a cloud KMS adapter
     *                         ({@code aws-kms}, {@code gcp-kms}, {@code azure-key-vault}) that provides its own
     *                         key wrapper
     * @param keyId            id of the current local key (blank → derived from the key)
     * @param key              current local AES-256 key, base64 ({@code IDENTITY_DATA_KEY})
     * @param previousKeys     retired local keys kept for decryption during a rotation, comma-separated
     *                         {@code [keyId:]base64} ({@code IDENTITY_DATA_KEYS_PREVIOUS})
     * @param sealOnStartup    encrypt legacy plaintext TOTP secrets at start-up (default true)
     */
    public record DataProtection(String provider, String keyId, String key, String previousKeys,
                                 Boolean sealOnStartup) {

        public DataProtection {
            provider = provider == null || provider.isBlank() ? "local" : provider.trim();
            sealOnStartup = sealOnStartup == null ? Boolean.TRUE : sealOnStartup;
        }

        /** Never prints key material. */
        @Override
        public String toString() {
            return "DataProtection[provider=" + provider + ", keyId=" + keyId + ", sealOnStartup=" + sealOnStartup
                    + "]";
        }
    }

    /** @param acceptUrl Seller Center page that accepts an invitation ({@code ?token=} is appended) */
    public record Invitations(String acceptUrl) {
    }

    /** @param clientIds confidential clients allowed to use the first-party sign-in API (default gateway-bff) */
    public record FirstParty(List<String> clientIds) {

        public FirstParty {
            clientIds = clientIds == null || clientIds.isEmpty() ? List.of("gateway-bff") : List.copyOf(clientIds);
        }
    }

    /** Lockout limits; defaults: 5 failures per account / 30 per IP within 15 minutes, 15 min doubling to 4 h. */
    public record SignInProtection(Limit account, Limit ip) {

        public SignInProtection {
            account = account == null ? new Limit(5, null, null, null) : account;
            ip = ip == null ? new Limit(30, null, null, null) : ip;
        }
    }

    /**
     * @param maxFailures failed attempts within {@code window} that lock the key
     * @param lockout     first lockout; doubled for every consecutive lockout up to {@code maxLockout}
     */
    public record Limit(int maxFailures, Duration window, Duration lockout, Duration maxLockout) {

        public Limit {
            window = window == null ? Duration.ofMinutes(15) : window;
            lockout = lockout == null ? Duration.ofMinutes(15) : lockout;
            maxLockout = maxLockout == null ? Duration.ofHours(4) : maxLockout;
        }
    }

    /**
     * @param keyId                 {@code kid} header
     * @param privateKeyPem         PKCS#8 RSA private key (env {@code SIGNING_KEY_PEM})
     * @param generateIfMissing     generate an ephemeral key when no PEM is set (local and tests only)
     * @param previousPublicKeysPem public keys of retired signing keys, still published in the JWKS during rotation
     */
    public record Signing(String keyId, String privateKeyPem, boolean generateIfMissing,
                          List<String> previousPublicKeysPem) {

        public Signing {
            keyId = keyId == null || keyId.isBlank() ? "identity-1" : keyId;
            previousPublicKeysPem = previousPublicKeysPem == null ? List.of() : List.copyOf(previousPublicKeysPem);
        }
    }

    /** @param sender {@code outbox} (notifications service delivers) or {@code log} (local) */
    public record Verification(String sender) {

        public Verification {
            sender = sender == null || sender.isBlank() ? "outbox" : sender;
        }
    }

    /**
     * @param issuerName          shown in authenticator apps
     * @param secureCookies       mark the trusted-browser cookie Secure (off only for plain-http local)
     */
    public record Mfa(String issuerName, Boolean secureCookies) {

        public Mfa {
            issuerName = issuerName == null ? "AfroTransact" : issuerName;
            secureCookies = secureCookies == null ? Boolean.TRUE : secureCookies;
        }
    }

    /**
     * Passkey relying party: id (registrable domain), display name and the origins allowed to use it.
     *
     * @param appOrigins S016: native app origins for platform passkeys (Face ID / fingerprint), e.g.
     *                   {@code android:apk-key-hash:<base64url SHA-256 of the signing certificate>}; merged into
     *                   {@code allowedOrigins}. iOS apps sign with the web origin of their associated domain.
     */
    public record WebAuthn(String rpId, String rpName, List<String> allowedOrigins, List<String> appOrigins) {

        public WebAuthn {
            rpId = rpId == null ? "localhost" : rpId;
            rpName = rpName == null ? "AfroTransact" : rpName;
            appOrigins = appOrigins == null ? List.of() : appOrigins.stream().filter(o -> o != null && !o.isBlank())
                    .map(String::trim).toList();
            List<String> all = new ArrayList<>(allowedOrigins == null ? List.of() : allowedOrigins);
            appOrigins.stream().filter(o -> !all.contains(o)).forEach(all::add);
            allowedOrigins = List.copyOf(all);
        }
    }

    /** OAuth client credentials of a social provider; providers without a client id are not registered. */
    public record Social(String clientId, String clientSecret) {
    }

    public record BootstrapAdmin(String email, String password, String firstName, String lastName, String phone) {
    }

    /**
     * A registered OAuth client.
     *
     * @param type                {@code public} (PKCE, no secret) or {@code confidential}
     * @param secret              raw secret for confidential clients (from env); hashed before storage
     * @param publicRefreshTokens allow refresh tokens for a public client (mobile apps), rotated on every use
     */
    public record Client(String clientId, String name, String type, String secret, List<String> grantTypes,
                         List<String> redirectUris, List<String> postLogoutRedirectUris, List<String> scopes,
                         boolean requireConsent, boolean publicRefreshTokens, Duration accessTokenTtl,
                         Duration refreshTokenTtl) {

        public Client {
            name = name == null ? clientId : name;
            type = type == null ? "public" : type;
            grantTypes = grantTypes == null ? List.of() : List.copyOf(grantTypes);
            redirectUris = redirectUris == null ? List.of() : List.copyOf(redirectUris);
            postLogoutRedirectUris = postLogoutRedirectUris == null ? List.of() : List.copyOf(postLogoutRedirectUris);
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
            accessTokenTtl = accessTokenTtl == null ? Duration.ofMinutes(5) : accessTokenTtl;
            refreshTokenTtl = refreshTokenTtl == null ? Duration.ofDays(30) : refreshTokenTtl;
        }

        public boolean isPublic() {
            return "public".equalsIgnoreCase(type);
        }
    }
}
