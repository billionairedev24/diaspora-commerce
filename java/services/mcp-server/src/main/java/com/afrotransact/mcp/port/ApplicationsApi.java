package com.afrotransact.mcp.port;

/** seller-service onboarding applications (staff review queue). */
public interface ApplicationsApi {
    Downstream queue(String status, int page, int size, String bearerToken);

    Downstream application(String applicationId, String bearerToken);
}
