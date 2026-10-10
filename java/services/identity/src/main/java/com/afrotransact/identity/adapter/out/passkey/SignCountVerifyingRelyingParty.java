package com.afrotransact.identity.adapter.out.passkey;

import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialCreationOptionsRequest;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialRequestOptionsRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyRegistrationRequest;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;

/**
 * Enforces the WebAuthn signature counter (WebAuthn L3 §6.1.1, step 22 of §7.2) for the hosted pages and the
 * first-party API. Spring Security 7.1's {@code Webauthn4JRelyingPartyOperations} stores the counter after every
 * sign-in but verifies assertions against the counter in the registration attestation (usually 0), so a cloned
 * authenticator replaying older counter values was accepted (found by PasskeyCeremonyIT, S203). An assertion whose
 * counter does not exceed the stored one is rejected, unless both are 0 (authenticators without a counter, such as
 * synced passkeys).
 */
public final class SignCountVerifyingRelyingParty implements WebAuthnRelyingPartyOperations {

    private static final Logger LOG = LoggerFactory.getLogger(SignCountVerifyingRelyingParty.class);
    /** rpIdHash (32) + flags (1), then the 4-byte big-endian signature counter. */
    private static final int COUNTER_OFFSET = 33;

    private final WebAuthnRelyingPartyOperations delegate;
    private final UserCredentialRepository credentials;

    public SignCountVerifyingRelyingParty(WebAuthnRelyingPartyOperations delegate,
                                          UserCredentialRepository credentials) {
        this.delegate = delegate;
        this.credentials = credentials;
    }

    @Override
    public PublicKeyCredentialCreationOptions createPublicKeyCredentialCreationOptions(
            PublicKeyCredentialCreationOptionsRequest request) {
        return delegate.createPublicKeyCredentialCreationOptions(request);
    }

    @Override
    public CredentialRecord registerCredential(RelyingPartyRegistrationRequest request) {
        return delegate.registerCredential(request);
    }

    @Override
    public PublicKeyCredentialRequestOptions createCredentialRequestOptions(
            PublicKeyCredentialRequestOptionsRequest request) {
        return delegate.createCredentialRequestOptions(request);
    }

    @Override
    public PublicKeyCredentialUserEntity authenticate(RelyingPartyAuthenticationRequest request) {
        Bytes credentialId = request.getPublicKey().getRawId();
        CredentialRecord stored = credentialId == null ? null : credentials.findByCredentialId(credentialId);
        byte[] authData = request.getPublicKey().getResponse().getAuthenticatorData().getBytes();
        if (stored != null && authData.length >= COUNTER_OFFSET + 4) {
            long presented = Integer.toUnsignedLong(ByteBuffer.wrap(authData, COUNTER_OFFSET, 4).getInt());
            long last = stored.getSignatureCount();
            if ((presented > 0 || last > 0) && presented <= last) {
                LOG.warn("Passkey {} presented signature counter {} <= stored {}: possible cloned authenticator",
                        credentialId.toBase64UrlString(), presented, last);
                throw new IllegalArgumentException("Signature counter did not increase");
            }
        }
        return delegate.authenticate(request);
    }
}
