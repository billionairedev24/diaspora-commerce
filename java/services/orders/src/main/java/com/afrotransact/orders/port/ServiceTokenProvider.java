package com.afrotransact.orders.port;

/** Client-credentials access token of the orders service (client id orders-service), cached until near expiry. */
public interface ServiceTokenProvider {

    String token();
}
