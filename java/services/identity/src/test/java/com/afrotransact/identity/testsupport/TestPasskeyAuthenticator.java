package com.afrotransact.identity.testsupport;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;

import com.webauthn4j.converter.AttestationObjectConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.attestation.AttestationObject;
import com.webauthn4j.data.attestation.authenticator.AAGUID;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData;
import com.webauthn4j.data.attestation.authenticator.EC2COSEKey;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.extension.authenticator.RegistrationExtensionAuthenticatorOutput;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;

/**
 * A software passkey (ES256, "none" attestation) for tests: stores its credential the way Spring Security's
 * registration does, and produces {@code navigator.credentials.get()} results for a challenge and origin.
 */
public final class TestPasskeyAuthenticator {

    private static final byte FLAG_UP = 0x01;
    private static final byte FLAG_UV = 0x04;
    private static final byte FLAG_AT = 0x40;
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final String rpId;
    private final KeyPair keys;
    private final byte[] credentialId = new byte[16];
    private final ObjectConverter converter = new ObjectConverter();
    private Bytes userHandle;
    private int signCount;

    public TestPasskeyAuthenticator(String rpId) {
        this.rpId = rpId;
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            this.keys = gen.generateKeyPair();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
        new SecureRandom().nextBytes(credentialId);
    }

    /** Registers the passkey for an account (WebAuthn user entity name = account id). */
    public void register(PublicKeyCredentialUserEntityRepository users, UserCredentialRepository credentials,
                        String accountId) {
        PublicKeyCredentialUserEntity entity = users.findByUsername(accountId);
        if (entity == null) {
            entity = ImmutablePublicKeyCredentialUserEntity.builder().name(accountId).id(Bytes.random())
                    .displayName(accountId).build();
            users.save(entity);
        }
        userHandle = entity.getId();
        AttestedCredentialData attested = new AttestedCredentialData(AAGUID.ZERO, credentialId,
                EC2COSEKey.create((ECPublicKey) keys.getPublic(), COSEAlgorithmIdentifier.ES256));
        AuthenticatorData<RegistrationExtensionAuthenticatorOutput> authData = new AuthenticatorData<>(
                sha256(rpId.getBytes(StandardCharsets.UTF_8)), (byte) (FLAG_UP | FLAG_UV | FLAG_AT), 0, attested);
        byte[] attestationObject = new AttestationObjectConverter(converter)
                .convertToBytes(new AttestationObject(authData, new NoneAttestationStatement()));
        byte[] cose = converter.getCborConverter().writeValueAsBytes(attested.getCOSEKey());
        credentials.save(ImmutableCredentialRecord.builder()
                .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
                .credentialId(new Bytes(credentialId))
                .userEntityUserId(entity.getId())
                .publicKey(new ImmutablePublicKeyCose(cose))
                .signatureCount(0)
                .uvInitialized(true)
                .backupEligible(false)
                .backupState(false)
                .transports(Set.of())
                .attestationObject(new Bytes(attestationObject))
                .created(Instant.now())
                .lastUsed(Instant.now())
                .label("Test passkey")
                .build());
    }

    /** The PublicKeyCredential JSON a browser would return for this challenge, signed by this passkey. */
    public String assertion(String challenge, String origin) {
        signCount++;
        String clientData = "{\"type\":\"webauthn.get\",\"challenge\":\"" + challenge + "\",\"origin\":\"" + origin
                + "\",\"crossOrigin\":false}";
        byte[] clientDataJson = clientData.getBytes(StandardCharsets.UTF_8);
        byte[] authData = ByteBuffer.allocate(37).put(sha256(rpId.getBytes(StandardCharsets.UTF_8)))
                .put((byte) (FLAG_UP | FLAG_UV)).putInt(signCount).array();
        byte[] signature;
        try {
            Signature ecdsa = Signature.getInstance("SHA256withECDSA");
            ecdsa.initSign(keys.getPrivate());
            ecdsa.update(authData);
            ecdsa.update(sha256(clientDataJson));
            signature = ecdsa.sign();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
        String id = B64.encodeToString(credentialId);
        return "{\"id\":\"" + id + "\",\"rawId\":\"" + id + "\",\"type\":\"public-key\",\"response\":{"
                + "\"authenticatorData\":\"" + B64.encodeToString(authData) + "\","
                + "\"clientDataJSON\":\"" + B64.encodeToString(clientDataJson) + "\","
                + "\"signature\":\"" + B64.encodeToString(signature) + "\","
                + "\"userHandle\":\"" + userHandle.toBase64UrlString() + "\"},"
                + "\"clientExtensionResults\":{},\"authenticatorAttachment\":\"platform\"}";
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
