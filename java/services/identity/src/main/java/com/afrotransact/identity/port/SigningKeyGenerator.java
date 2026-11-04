package com.afrotransact.identity.port;

/** Creates the key pair of a new signing key (RSA 2048, RS256). */
public interface SigningKeyGenerator {

    /** PEM texts: an X.509 public key and a PKCS#8 private key. */
    record Generated(String publicKeyPem, String privateKeyPem) {

        /** Never prints the private key. */
        @Override
        public String toString() {
            return "Generated[publicKeyPem=" + publicKeyPem + ", privateKeyPem=<hidden>]";
        }
    }

    Generated generate();
}
