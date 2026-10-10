package com.afrotransact.identity.adapter.out.passkey;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.port.PasskeyAssertions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.security.web.webauthn.jackson.WebauthnJacksonModule;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * Passkey assertions for the first-party sign-in API, verified by the same Spring Security / WebAuthn4J relying party
 * as the hosted {@code /login/webauthn} (same credential repositories, RP id and allowed origins). The request asks for
 * a discoverable credential (no allow-list, so no account enumeration) with user verification required, because a
 * passkey sign-in skips the second factor.
 */
public class WebAuthnPasskeyAssertions implements PasskeyAssertions {

    private static final Logger LOG = LoggerFactory.getLogger(WebAuthnPasskeyAssertions.class);
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    private final WebAuthnRelyingPartyOperations relyingParty;
    private final String rpId;
    private final JsonMapper json = JsonMapper.builder().addModule(new WebauthnJacksonModule()).build();
    private final JavaType credentialType = json.getTypeFactory()
            .constructParametricType(PublicKeyCredential.class, AuthenticatorAssertionResponse.class);

    public WebAuthnPasskeyAssertions(WebAuthnRelyingPartyOperations relyingParty, String rpId) {
        this.relyingParty = relyingParty;
        this.rpId = rpId;
    }

    @Override
    public PasskeyRequest newRequest() {
        PublicKeyCredentialRequestOptions options = options(Bytes.random(), rpId);
        return new PasskeyRequest(options.getChallenge().toBase64UrlString(), rpId, json.writeValueAsString(options));
    }

    @Override
    public Optional<UUID> verify(PasskeyRequest request, String credentialJson) {
        try {
            PublicKeyCredential<AuthenticatorAssertionResponse> credential = json.readValue(credentialJson,
                    credentialType);
            PublicKeyCredentialUserEntity owner = relyingParty.authenticate(new RelyingPartyAuthenticationRequest(
                    options(Bytes.fromBase64(request.challenge()), request.rpId()), credential));
            return Optional.of(UUID.fromString(owner.getName()));
        } catch (RuntimeException rejected) {
            LOG.info("Passkey assertion rejected: {}", rejected.getClass().getSimpleName());
            LOG.debug("Passkey assertion rejected", rejected);
            return Optional.empty();
        }
    }

    private static PublicKeyCredentialRequestOptions options(Bytes challenge, String rpId) {
        return PublicKeyCredentialRequestOptions.builder()
                .challenge(challenge)
                .rpId(rpId)
                .timeout(TIMEOUT)
                .userVerification(UserVerificationRequirement.REQUIRED)
                .build();
    }
}
