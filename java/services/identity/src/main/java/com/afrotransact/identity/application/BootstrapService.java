package com.afrotransact.identity.application;

/** Start-up provisioning: the first super administrator (from env/config) so access can be administered. */
public interface BootstrapService {

    /** Creates the account with {@code platform.super_admin} if no account uses {@code email}. Idempotent. */
    void ensureSuperAdmin(String email, String password, String firstName, String lastName, String phone);
}
