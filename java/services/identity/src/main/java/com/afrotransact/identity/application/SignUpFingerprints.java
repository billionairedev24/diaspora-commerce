package com.afrotransact.identity.application;

/**
 * Keyed hashes of what a sign-up shows about the browser and network, published with {@code identity.user.registered}
 * so the referral program can tell that a referrer and the friend they referred signed up from the same place (S322).
 * The raw device id and address never leave identity.
 */
public interface SignUpFingerprints {

    /** Hex digest of the device cookie, or null when there is none. */
    String device(String deviceId);

    /** Hex digest of the address's network (IPv4 /24, IPv6 /64), or null when it is missing or not an address. */
    String network(String clientAddress);
}
