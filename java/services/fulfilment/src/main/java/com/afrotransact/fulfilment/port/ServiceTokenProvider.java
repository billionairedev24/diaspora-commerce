package com.afrotransact.fulfilment.port;

/** Client-credentials access token of fulfilment-service (client id fulfilment-service), cached until near expiry. */
public interface ServiceTokenProvider {

    String token();
}
