package com.afrotransact.mcp.port;

/** seller-service onboarding applications (staff review queue). */
public interface ApplicationsApi {
    Downstream queue(String status, int page, int size, CallerCredentials credentials);

    Downstream application(String applicationId, CallerCredentials credentials);
}
