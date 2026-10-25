package com.afrotransact.identity.port;

/** The in-memory view of the signing keys that signs tokens and fills the JWKS; other replicas re-read the table. */
public interface SigningKeyCache {

    /** Forget the loaded keys so the next token or JWKS request reads the table (after a rotation on this replica). */
    void invalidate();
}
