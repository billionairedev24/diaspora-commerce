package com.afrotransact.identity.adapter.out.crypto;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** PEM text for RSA keys: X.509 public keys and PKCS#8 private keys. */
public final class Pem {

    private Pem() {
    }

    public static String publicKey(RSAPublicKey key) {
        return pem("PUBLIC KEY", key.getEncoded());
    }

    public static String privateKey(PrivateKey key) {
        return pem("PRIVATE KEY", key.getEncoded());
    }

    public static RSAPublicKey rsaPublic(String pem) {
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der(pem)));
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("Not an X.509 RSA public key", ex);
        }
    }

    public static RSAPrivateCrtKey rsaPrivate(String pem) {
        try {
            return (RSAPrivateCrtKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der(pem)));
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException ex) {
            throw new IllegalStateException("Not a PKCS#8 RSA private key", ex);
        }
    }

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    private static byte[] der(String pem) {
        String body = pem.replace("\\n", "\n").replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
