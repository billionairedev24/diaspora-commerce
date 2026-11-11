package com.afrotransact.seller.application;

/**
 * D11: the bakery flag is replaced by the verified FOOD_SERVICE capability. Every organisation that claimed to be a
 * bakery gets a PENDING food service application for staff to review; nothing is ever approved automatically (no
 * permit is on file), and the flag's column stays in the catalog until the migration is done.
 */
public interface FoodServiceMigrationService {

    /** Creates the missing applications; idempotent (one per organisation, never a second after a decision). Returns how many were created. */
    int run();
}
