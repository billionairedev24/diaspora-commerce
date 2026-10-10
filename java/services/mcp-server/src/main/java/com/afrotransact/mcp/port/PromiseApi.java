package com.afrotransact.mcp.port;

/** promise-service delivery promise. */
public interface PromiseApi {
    Downstream promise(String atin, String zip, String bearerToken);
}
