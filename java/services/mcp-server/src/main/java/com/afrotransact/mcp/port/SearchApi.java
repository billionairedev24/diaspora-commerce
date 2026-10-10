package com.afrotransact.mcp.port;

import java.util.Map;

/** search-service {@code GET /v1/search}. */
public interface SearchApi {
    Downstream search(Map<String, Object> query, String bearerToken);
}
