package com.afrotransact.identity.domain;

import java.util.List;

/** Password strength rules shared by sign-up, password change and admin-created accounts. */
public interface PasswordPolicy {

    int MIN_LENGTH = 12;
    int MAX_LENGTH = 128;

    /**
     * Returns every rule the password breaks (empty when acceptable).
     *
     * @param personal values the password must not contain: first name, last name, email local part
     */
    List<String> violations(String password, List<String> personal);
}
