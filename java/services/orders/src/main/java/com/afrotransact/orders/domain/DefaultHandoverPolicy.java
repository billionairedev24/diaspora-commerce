package com.afrotransact.orders.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.random.RandomGenerator;

/** Default {@link HandoverPolicy}: SHA-256(shipmentId:code), SecureRandom codes 0000–9999. */
public final class DefaultHandoverPolicy implements HandoverPolicy {

    private final RandomGenerator random;

    public DefaultHandoverPolicy() {
        this(new SecureRandom());
    }

    public DefaultHandoverPolicy(RandomGenerator random) {
        this.random = random;
    }

    @Override
    public boolean required(boolean perishable, DeliveryMethod method, DeliveryPreferences preferences) {
        if (method == DeliveryMethod.CARRIER) {
            return false;
        }
        return perishable || preferences != null && preferences.handToCustomer();
    }

    @Override
    public String newCode() {
        return String.format("%04d", random.nextInt(10_000));
    }

    @Override
    public String hash(String shipmentId, String code) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest((shipmentId + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Override
    public boolean matches(String shipmentId, String code, String storedHash) {
        if (code == null || storedHash == null || !code.trim().matches("\\d{4}")) {
            return false;
        }
        return MessageDigest.isEqual(hash(shipmentId, code.trim()).getBytes(StandardCharsets.US_ASCII),
                storedHash.getBytes(StandardCharsets.US_ASCII));
    }
}
