package com.afrotransact.seller.port;

/** Field-level encryption for sensitive values (SSN/ITIN/EIN). Ciphertexts carry a key id for rotation. */
public interface FieldCipher {

    String encrypt(String plaintext);

    String decrypt(String ciphertext);
}
