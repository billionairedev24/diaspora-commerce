package com.afrotransact.identity.domain;

/** How a first-party sign-in was completed (recorded on the login ticket and in the audit log). */
public enum SignInMethod {
    PASSWORD,
    PASSWORD_MFA,
    PASSKEY
}
