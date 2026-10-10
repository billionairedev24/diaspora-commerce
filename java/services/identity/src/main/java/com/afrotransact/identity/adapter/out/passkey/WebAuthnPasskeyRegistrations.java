package com.afrotransact.identity.adapter.out.passkey;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.Passkey;
import com.afrotransact.identity.port.PasskeyRegistrations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.webauthn.api.AttestationConveyancePreference;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorSelectionCriteria;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutableAuthenticationExtensionsClientInput;
import org.springframework.security.web.webauthn.api.ImmutableAuthenticationExtensionsClientInputs;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialDescriptor;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialParameters;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.ResidentKeyRequirement;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.security.web.webauthn.jackson.WebauthnJacksonModule;
import org.springframework.security.web.webauthn.management.ImmutableRelyingPartyRegistrationRequest;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * Passkey registration for the first-party API and the mobile apps, verified by the same Spring Security / WebAuthn4J
 * relying party, repositories, RP id and allowed origins as the hosted {@code /webauthn/register} page (so a passkey
 * registered here signs in on the hosted page and through {@code /v1/auth/passkey/*}, and vice versa). The WebAuthn user
 * entity's name is the account id, as on the hosted page. Unlike the hosted page (user verification preferred), user
 * verification is required: a passkey sign-in skips the second factor, and on mobile it is the Face ID / fingerprint
 * check. Native apps sign with their own origin ({@code android:apk-key-hash:…}), which must be listed in
 * {@code at.identity.webauthn.allowed-origins}; iOS apps use the web origin of their associated domain.
 */
public class WebAuthnPasskeyRegistrations implements PasskeyRegistrations {

    private static final Logger LOG = LoggerFactory.getLogger(WebAuthnPasskeyRegistrations.class);
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    private final WebAuthnRelyingPartyOperations relyingParty;
    private final PublicKeyCredentialUserEntityRepository userEntities;
    private final UserCredentialRepository credentials;
    private final PublicKeyCredentialRpEntity rp;
    private final JsonMapper json = JsonMapper.builder().addModule(new WebauthnJacksonModule()).build();
    private final JavaType credentialType = json.getTypeFactory()
            .constructParametricType(PublicKeyCredential.class, AuthenticatorAttestationResponse.class);

    public WebAuthnPasskeyRegistrations(WebAuthnRelyingPartyOperations relyingParty,
                                        PublicKeyCredentialUserEntityRepository userEntities,
                                        UserCredentialRepository credentials, PublicKeyCredentialRpEntity rp) {
        this.relyingParty = relyingParty;
        this.userEntities = userEntities;
        this.credentials = credentials;
        this.rp = rp;
    }

    @Override
    public RegistrationRequest newRegistration(UUID accountId, String displayName) {
        PublicKeyCredentialUserEntity user = userEntity(accountId, displayName);
        PublicKeyCredentialCreationOptions options = options(Bytes.random(), user,
                excluded(credentials.findByUserId(user.getId())));
        return new RegistrationRequest(options.getChallenge().toBase64UrlString(), user.getId().toBase64UrlString(),
                rp.getId(), json.writeValueAsString(options));
    }

    @Override
    public Optional<Passkey> register(RegistrationRequest request, String credentialJson, String label) {
        try {
            PublicKeyCredential<AuthenticatorAttestationResponse> credential = json.readValue(credentialJson,
                    credentialType);
            PublicKeyCredentialUserEntity user = userEntities.findById(Bytes.fromBase64(request.userHandle()));
            if (user == null) {
                return Optional.empty();
            }
            PublicKeyCredentialCreationOptions options = options(Bytes.fromBase64(request.challenge()), user,
                    List.of());
            CredentialRecord saved = relyingParty.registerCredential(new ImmutableRelyingPartyRegistrationRequest(
                    options, new RelyingPartyPublicKey(credential, label)));
            return Optional.of(new Passkey(saved.getCredentialId().toBase64UrlString(), saved.getLabel(),
                    saved.getCreated(), saved.getLastUsed()));
        } catch (RuntimeException rejected) {
            LOG.info("Passkey registration rejected: {}", rejected.getClass().getSimpleName());
            LOG.debug("Passkey registration rejected", rejected);
            return Optional.empty();
        }
    }

    @Override
    public boolean rename(UUID accountId, String credentialId, String label) {
        PublicKeyCredentialUserEntity user = userEntities.findByUsername(accountId.toString());
        if (user == null) {
            return false;
        }
        for (CredentialRecord c : credentials.findByUserId(user.getId())) {
            if (c.getCredentialId().toBase64UrlString().equals(credentialId)) {
                credentials.save(ImmutableCredentialRecord.fromCredentialRecord(c).label(label).build());
                return true;
            }
        }
        return false;
    }

    private PublicKeyCredentialUserEntity userEntity(UUID accountId, String displayName) {
        PublicKeyCredentialUserEntity found = userEntities.findByUsername(accountId.toString());
        if (found != null) {
            return found;
        }
        PublicKeyCredentialUserEntity created = ImmutablePublicKeyCredentialUserEntity.builder()
                .name(accountId.toString())
                .id(Bytes.random())
                .displayName(displayName == null || displayName.isBlank() ? accountId.toString() : displayName)
                .build();
        userEntities.save(created);
        return created;
    }

    private PublicKeyCredentialCreationOptions options(Bytes challenge, PublicKeyCredentialUserEntity user,
                                                       List<PublicKeyCredentialDescriptor> exclude) {
        return PublicKeyCredentialCreationOptions.builder()
                .attestation(AttestationConveyancePreference.NONE)
                .pubKeyCredParams(PublicKeyCredentialParameters.EdDSA, PublicKeyCredentialParameters.ES256,
                        PublicKeyCredentialParameters.RS256)
                .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                        .userVerification(UserVerificationRequirement.REQUIRED)
                        .residentKey(ResidentKeyRequirement.REQUIRED)
                        .build())
                .challenge(challenge)
                .extensions(new ImmutableAuthenticationExtensionsClientInputs(
                        ImmutableAuthenticationExtensionsClientInput.credProps))
                .timeout(TIMEOUT)
                .user(user)
                .rp(rp)
                .excludeCredentials(exclude)
                .build();
    }

    private static List<PublicKeyCredentialDescriptor> excluded(List<CredentialRecord> records) {
        List<PublicKeyCredentialDescriptor> result = new ArrayList<>();
        for (CredentialRecord r : records) {
            result.add(PublicKeyCredentialDescriptor.builder().id(r.getCredentialId())
                    .transports(r.getTransports()).build());
        }
        return result;
    }
}
