package com.afrotransact.seller.port;

/** Reads the current caller's identity claims from their access token. */
public interface CallerProfileProvider {

    CallerProfile current();
}
