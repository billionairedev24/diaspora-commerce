package com.afrotransact.identity.testsupport;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
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
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A pure-Java platform authenticator for headless WebAuthn ceremonies (what Chromium's CDP virtual authenticator
 * does, without a browser): one discoverable ES256 credential on an EC P-256 key pair, user presence and user
 * verification always asserted, "none" attestation. It builds the attestation object and authenticator data byte by
 * byte (CBOR via {@link Cbor}), signs {@code authenticatorData || SHA-256(clientDataJSON)} and returns the JSON that
 * Spring Security's {@code webauthn.js} / {@code PublicKeyCredential.toJSON()} would post.
 *
 * <p>Unlike a browser it lets a test claim any origin and reuse any challenge, which is how the negative cases
 * (phishing origin, replay, cloned authenticator) are exercised.</p>
 */
public final class VirtualAuthenticator {

    private static final byte FLAG_UP = 0x01;
    private static final byte FLAG_UV = 0x04;
    private static final byte FLAG_AT = 0x40;
    private static final int COSE_ES256 = -7;
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DECODER = Base64.getUrlDecoder();

    private final String rpId;
    private final KeyPair keys;
    private final byte[] credentialId = new byte[32];
    private byte[] userHandle;
    private int signCount;

    public VirtualAuthenticator(String rpId) {
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

    /** Base64url credential id (the id the server lists and deletes). */
    public String credentialId() {
        return B64URL.encodeToString(credentialId);
    }

    public int signCount() {
        return signCount;
    }

    /**
     * {@code navigator.credentials.create()}: makes the credential for the user handle in the creation options and
     * returns the registration body Spring Security's {@code /webauthn/register} expects.
     *
     * @param challenge  base64url challenge from the creation options
     * @param userHandle base64url {@code user.id} from the creation options
     * @param origin     origin the "browser" reports in clientDataJSON
     */
    public String create(String challenge, String userHandle, String origin, String label) {
        return "{\"publicKey\":{\"credential\":" + credential(challenge, userHandle, origin) + ",\"label\":\"" + label
                + "\"}}";
    }

    /**
     * {@code navigator.credentials.create()} as {@code PublicKeyCredential.toJSON()}: the credential alone, as the
     * first-party API ({@code POST /v1/me/passkeys}) and the mobile platform passkey APIs send it.
     */
    public String credential(String challenge, String userHandle, String origin) {
        this.userHandle = B64URL_DECODER.decode(userHandle);
        byte[] clientDataJson = clientData("webauthn.create", challenge, origin);
        byte[] authData = concat(rpIdHash(), new byte[] {(byte) (FLAG_UP | FLAG_UV | FLAG_AT)}, counter(signCount),
                new byte[16], ByteBuffer.allocate(2).putShort((short) credentialId.length).array(), credentialId,
                Cbor.encode(coseKey()));
        Map<String, Object> attestation = new LinkedHashMap<>();
        attestation.put("fmt", "none");
        attestation.put("attStmt", Map.of());
        attestation.put("authData", authData);
        String id = credentialId();
        return "{\"id\":\"" + id + "\",\"rawId\":\"" + id + "\",\"response\":{"
                + "\"attestationObject\":\"" + B64URL.encodeToString(Cbor.encode(attestation)) + "\","
                + "\"clientDataJSON\":\"" + B64URL.encodeToString(clientDataJson) + "\","
                + "\"transports\":[\"internal\"]},\"type\":\"public-key\",\"clientExtensionResults\":{},"
                + "\"authenticatorAttachment\":\"platform\"}";
    }

    /** {@code navigator.credentials.get()} with the next signature counter. */
    public String get(String challenge, String origin) {
        signCount++;
        return assertion(challenge, origin, signCount);
    }

    /** An assertion with a chosen counter, as a cloned copy of this authenticator would produce. */
    public String getWithCounter(String challenge, String origin, int counter) {
        return assertion(challenge, origin, counter);
    }

    private String assertion(String challenge, String origin, int counter) {
        if (userHandle == null) {
            throw new IllegalStateException("No credential: run create() first");
        }
        byte[] clientDataJson = clientData("webauthn.get", challenge, origin);
        byte[] authData = concat(rpIdHash(), new byte[] {(byte) (FLAG_UP | FLAG_UV)}, counter(counter));
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
        String id = credentialId();
        return "{\"id\":\"" + id + "\",\"rawId\":\"" + id + "\",\"type\":\"public-key\",\"response\":{"
                + "\"authenticatorData\":\"" + B64URL.encodeToString(authData) + "\","
                + "\"clientDataJSON\":\"" + B64URL.encodeToString(clientDataJson) + "\","
                + "\"signature\":\"" + B64URL.encodeToString(signature) + "\","
                + "\"userHandle\":\"" + B64URL.encodeToString(userHandle) + "\"},"
                + "\"clientExtensionResults\":{},\"authenticatorAttachment\":\"platform\"}";
    }

    /** COSE_Key (RFC 9053) in CTAP2 canonical order: kty EC2, alg ES256, crv P-256, x, y. */
    private Map<Object, Object> coseKey() {
        ECPublicKey pub = (ECPublicKey) keys.getPublic();
        Map<Object, Object> cose = new LinkedHashMap<>();
        cose.put(1, 2);
        cose.put(3, COSE_ES256);
        cose.put(-1, 1);
        cose.put(-2, unsigned32(pub.getW().getAffineX()));
        cose.put(-3, unsigned32(pub.getW().getAffineY()));
        return cose;
    }

    private static byte[] clientData(String type, String challenge, String origin) {
        return ("{\"type\":\"" + type + "\",\"challenge\":\"" + challenge + "\",\"origin\":\"" + origin
                + "\",\"crossOrigin\":false}").getBytes(StandardCharsets.UTF_8);
    }

    private byte[] rpIdHash() {
        return sha256(rpId.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] counter(int value) {
        return ByteBuffer.allocate(4).putInt(value).array();
    }

    private static byte[] unsigned32(BigInteger coordinate) {
        byte[] raw = coordinate.toByteArray();
        if (raw.length == 32) {
            return raw;
        }
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Arrays.stream(parts).forEach(out::writeBytes);
        return out.toByteArray();
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
