package com.afrotransact.payments.domain;

/** A fingerprint kept to compare referrer and friend: the sign-up device and network, a delivery address. */
public enum SignalKind {
    DEVICE,
    NETWORK,
    ADDRESS
}
