package com.afrotransact.mcp.domain;

/**
 * Masks personal data in staff tool output (application details): tax ids and account numbers to the last four,
 * dates of birth to the year, emails and phone numbers partially. Works on plain maps/lists so it stays
 * framework-free.
 */
public interface PiiMasker {

    /** Returns a masked deep copy of a JSON-like tree (maps, lists, strings, numbers, booleans). */
    Object mask(Object tree);
}
