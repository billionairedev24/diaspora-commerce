package com.afrotransact.catalog.application;

import java.util.List;

/**
 * The old bakery answer (D11): seller-service reads which organisations claim to be a bakery and opens a pending food
 * service application for each (never approved automatically). Service scope {@code catalog:bakery-claims.read}.
 */
public interface BakeryClaimService {

    String SERVICE_READ = "catalog:bakery-claims.read";

    List<String> organisations();
}
