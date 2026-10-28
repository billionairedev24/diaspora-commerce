package com.afrotransact.payments.port;

/** Keyed hash of the values compared for abuse checks (delivery addresses), so they are never stored in the clear. */
public interface SignalHasher {

    /** Hex digest of a normalised value. */
    String hash(String normalised);
}
