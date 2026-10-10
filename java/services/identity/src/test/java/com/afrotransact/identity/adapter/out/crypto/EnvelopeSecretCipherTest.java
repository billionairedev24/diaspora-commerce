package com.afrotransact.identity.adapter.out.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import com.afrotransact.identity.port.SecretCipher;
import com.afrotransact.identity.port.SecretCipherException;
import org.junit.jupiter.api.Test;

class EnvelopeSecretCipherTest {

    private static final String KEY_A = "mB3FPeNA2n3gpPjBDhziub681AZitNXX4hoUn2t/IQ0=";
    private static final String KEY_B = "diGeNCRjM04dmY4SGJwN5VKqVcQ/I0qD642Ca2Dc7kg=";
    private static final String SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";
    private static final String CONTEXT = "mfa_totp.secret:5b0f0a52-0000-4000-8000-000000000001";

    private static EnvelopeSecretCipher cipher(String keyId, String key, String previous) {
        return new EnvelopeSecretCipher(new LocalDataKeyWrapper(new DataProtectionSettings(keyId, key, previous)));
    }

    @Test
    void roundTripsAndNeverContainsThePlaintext() {
        EnvelopeSecretCipher c = cipher("kek-1", KEY_A, "");
        SecretCipher.Sealed sealed = c.seal(SECRET, CONTEXT);

        assertThat(sealed.keyId()).isEqualTo("kek-1");
        assertThat(c.keyIdOf(sealed.envelope())).isEqualTo("kek-1");
        assertThat(c.open(sealed.envelope(), CONTEXT)).isEqualTo(SECRET);
        assertThat(new String(sealed.envelope(), StandardCharsets.ISO_8859_1)).doesNotContain(SECRET)
                .doesNotContain(SECRET.substring(0, 8));
        assertThat(Base64.getEncoder().encodeToString(sealed.envelope())).doesNotContain(SECRET.substring(0, 8));
    }

    @Test
    void everyRecordGetsAFreshDataKeyAndNonce() {
        EnvelopeSecretCipher c = cipher("kek-1", KEY_A, "");
        Set<String> envelopes = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            envelopes.add(Base64.getEncoder().encodeToString(c.seal(SECRET, CONTEXT).envelope()));
        }
        assertThat(envelopes).as("same secret, same record → still distinct ciphertexts").hasSize(50);
    }

    @Test
    void detectsTamperingAnywhereInTheEnvelope() {
        EnvelopeSecretCipher c = cipher("kek-1", KEY_A, "");
        byte[] envelope = c.seal(SECRET, CONTEXT).envelope();
        for (int i = 0; i < envelope.length; i++) {
            byte[] tampered = Arrays.copyOf(envelope, envelope.length);
            tampered[i] ^= 0x01;
            int at = i;
            assertThatThrownBy(() -> c.open(tampered, CONTEXT)).as("bit flipped at byte %d", at)
                    .isInstanceOf(SecretCipherException.class);
        }
        assertThatThrownBy(() -> c.open(Arrays.copyOf(envelope, envelope.length - 1), CONTEXT))
                .isInstanceOf(SecretCipherException.class);
        assertThatThrownBy(() -> c.open(new byte[] {1}, CONTEXT)).isInstanceOf(SecretCipherException.class);
        assertThatThrownBy(() -> c.open(new byte[0], CONTEXT)).isInstanceOf(SecretCipherException.class);
    }

    @Test
    void ciphertextIsBoundToItsRecord() {
        EnvelopeSecretCipher c = cipher("kek-1", KEY_A, "");
        byte[] envelope = c.seal(SECRET, CONTEXT).envelope();
        assertThatThrownBy(() -> c.open(envelope, "mfa_totp.secret:5b0f0a52-0000-4000-8000-000000000002"))
                .as("copied onto another user's row").isInstanceOf(SecretCipherException.class);
    }

    @Test
    void wrongKeyCannotOpen() {
        byte[] envelope = cipher("kek-1", KEY_A, "").seal(SECRET, CONTEXT).envelope();

        assertThatThrownBy(() -> cipher("kek-1", KEY_B, "").open(envelope, CONTEXT))
                .as("same key id, different key material").isInstanceOf(SecretCipherException.class);
        assertThatThrownBy(() -> cipher("kek-2", KEY_B, "").open(envelope, CONTEXT))
                .as("key id not in the ring").isInstanceOf(SecretCipherException.class)
                .hasMessageContaining("Unknown data-protection key id kek-1");
    }

    @Test
    void previousKeysOpenOldEnvelopesWhileNewOnesUseTheCurrentKey() {
        byte[] old = cipher("kek-1", KEY_A, "").seal(SECRET, CONTEXT).envelope();
        EnvelopeSecretCipher rotated = cipher("kek-2", KEY_B, "kek-1:" + KEY_A);

        assertThat(rotated.keyIdOf(old)).isEqualTo("kek-1");
        assertThat(rotated.open(old, CONTEXT)).isEqualTo(SECRET);
        SecretCipher.Sealed resealed = rotated.seal(rotated.open(old, CONTEXT), CONTEXT);
        assertThat(resealed.keyId()).isEqualTo("kek-2");
        assertThatThrownBy(() -> cipher("kek-1", KEY_A, "").open(resealed.envelope(), CONTEXT))
                .as("the retired key alone cannot read re-sealed secrets").isInstanceOf(SecretCipherException.class);
    }

    @Test
    void keyIdsAreDerivedFromTheKeyWhenNotConfigured() {
        EnvelopeSecretCipher a = cipher("", KEY_A, "");
        assertThat(a.currentKeyId()).matches("k-[0-9a-f]{16}").isEqualTo(cipher(null, KEY_A, null).currentKeyId())
                .isNotEqualTo(cipher("", KEY_B, "").currentKeyId());
        byte[] old = a.seal(SECRET, CONTEXT).envelope();
        assertThat(cipher("", KEY_B, " " + KEY_A + " ").open(old, CONTEXT)).as("previous key without an id")
                .isEqualTo(SECRET);
    }

    @Test
    void rejectsMissingOrWeakKeys() {
        assertThatThrownBy(() -> cipher("", "", "")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IDENTITY_DATA_KEY is not set");
        assertThatThrownBy(() -> cipher("", "not base64!", "")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid base64");
        String aes128 = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> cipher("", aes128, "")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("256-bit");
        assertThatThrownBy(() -> cipher("", KEY_A, "old:" + aes128)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IDENTITY_DATA_KEYS_PREVIOUS");
    }

    @Test
    void settingsNeverPrintKeyMaterial() {
        String printed = new DataProtectionSettings("kek-1", KEY_A, "kek-0:" + KEY_B).toString();
        assertThat(printed).contains("kek-1").doesNotContain(KEY_A).doesNotContain(KEY_B);
    }
}
