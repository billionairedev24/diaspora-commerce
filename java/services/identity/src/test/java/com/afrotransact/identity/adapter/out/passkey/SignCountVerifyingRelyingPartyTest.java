package com.afrotransact.identity.adapter.out.passkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;

class SignCountVerifyingRelyingPartyTest {

    private static final Bytes CREDENTIAL_ID = new Bytes(new byte[] {1, 2, 3, 4});

    private final WebAuthnRelyingPartyOperations delegate = mock(WebAuthnRelyingPartyOperations.class);
    private final UserCredentialRepository credentials = mock(UserCredentialRepository.class);
    private final SignCountVerifyingRelyingParty relyingParty = new SignCountVerifyingRelyingParty(delegate,
            credentials);
    private final PublicKeyCredentialUserEntity owner = ImmutablePublicKeyCredentialUserEntity.builder()
            .name("5b0f0a52-0000-4000-8000-000000000001").id(Bytes.random()).displayName("Ada").build();

    private void stored(long signatureCount) {
        CredentialRecord record = mock(CredentialRecord.class);
        when(record.getSignatureCount()).thenReturn(signatureCount);
        when(credentials.findByCredentialId(CREDENTIAL_ID)).thenReturn(record);
        when(delegate.authenticate(any())).thenReturn(owner);
    }

    private static RelyingPartyAuthenticationRequest assertion(int counter) {
        byte[] authData = ByteBuffer.allocate(37).put(new byte[32]).put((byte) 0x05).putInt(counter).array();
        AuthenticatorAssertionResponse response = AuthenticatorAssertionResponse.builder()
                .authenticatorData(new Bytes(authData)).clientDataJSON(new Bytes(new byte[] {'{', '}'}))
                .signature(new Bytes(new byte[] {0})).build();
        PublicKeyCredential<AuthenticatorAssertionResponse> credential = PublicKeyCredential
                .<AuthenticatorAssertionResponse>builder().id(CREDENTIAL_ID.toBase64UrlString()).rawId(CREDENTIAL_ID)
                .type(PublicKeyCredentialType.PUBLIC_KEY).response(response).build();
        return new RelyingPartyAuthenticationRequest(PublicKeyCredentialRequestOptions.builder()
                .challenge(Bytes.random()).rpId("localhost").build(), credential);
    }

    @Test
    void acceptsARisingCounter() {
        stored(4);
        assertThat(relyingParty.authenticate(assertion(5))).isSameAs(owner);
    }

    @Test
    void rejectsACounterThatDidNotIncreaseBeforeVerifyingTheSignature() {
        stored(4);
        assertThatThrownBy(() -> relyingParty.authenticate(assertion(4))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> relyingParty.authenticate(assertion(2))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> relyingParty.authenticate(assertion(0))).as("counter dropped to zero")
                .isInstanceOf(IllegalArgumentException.class);
        verify(delegate, never()).authenticate(any());
    }

    @Test
    void authenticatorsWithoutACounterStayAtZero() {
        stored(0);
        assertThat(relyingParty.authenticate(assertion(0))).isSameAs(owner);
    }

    @Test
    void unknownCredentialsAreLeftToTheRelyingParty() {
        when(delegate.authenticate(any())).thenThrow(new IllegalArgumentException("Unable to find CredentialRecord"));
        assertThatThrownBy(() -> relyingParty.authenticate(assertion(1))).hasMessageContaining("Unable to find");
    }
}
