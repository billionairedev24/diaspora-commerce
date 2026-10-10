package com.afrotransact.seller.adapter.out.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AesGcmFieldCipherTest {

    private static final String K1 = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String K2 = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    @Test
    void roundTripsWithARandomIvAndKeyId() {
        AesGcmFieldCipher cipher = new AesGcmFieldCipher("k1", Map.of("k1", K1));
        String a = cipher.encrypt("123-45-6789");
        String b = cipher.encrypt("123-45-6789");
        assertThat(a).startsWith("k1:").isNotEqualTo(b).doesNotContain("6789");
        assertThat(cipher.decrypt(a)).isEqualTo("123-45-6789");
    }

    @Test
    void tamperingIsDetected() {
        AesGcmFieldCipher cipher = new AesGcmFieldCipher("k1", Map.of("k1", K1));
        String c = cipher.encrypt("12-3456789");
        byte[] raw = Base64.getDecoder().decode(c.substring(3));
        raw[raw.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt("k1:" + Base64.getEncoder().encodeToString(raw)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rotatedKeysStillDecryptOldValues() {
        String old = new AesGcmFieldCipher("k1", Map.of("k1", K1)).encrypt("123-45-6789");
        AesGcmFieldCipher rotated = new AesGcmFieldCipher("k2", Map.of("k1", K1, "k2", K2));
        assertThat(rotated.decrypt(old)).isEqualTo("123-45-6789");
        assertThat(rotated.encrypt("x")).startsWith("k2:");
    }

    @Test
    void refusesToStartWithoutAKey() {
        assertThatThrownBy(() -> new AesGcmFieldCipher("k1", Map.of())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AesGcmFieldCipher("k1", Map.of("k1", "c2hvcnQ=")))
                .isInstanceOf(IllegalStateException.class);
    }
}
