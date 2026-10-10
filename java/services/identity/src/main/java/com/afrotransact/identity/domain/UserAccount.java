package com.afrotransact.identity.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * A person's account. One account per email regardless of how many portals the person uses.
 *
 * @param passwordHash encoded password, or null for accounts that only use social sign-in or passkeys
 * @param activeOrgId  the seller/provider organisation the user currently acts for (token {@code org} claim)
 */
public record UserAccount(
        UUID id,
        String firstName,
        String lastName,
        String email,
        boolean emailVerified,
        String phone,
        String passwordHash,
        boolean enabled,
        boolean smsConsent,
        Instant termsAcceptedAt,
        String activeOrgId,
        Instant createdAt,
        Instant updatedAt,
        int version) {

    public UserAccount {
        email = normaliseEmail(email);
    }

    public static String normaliseEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    public String displayName() {
        return (firstName + " " + lastName).trim();
    }

    public String emailLocalPart() {
        int at = email.indexOf('@');
        return at < 0 ? email : email.substring(0, at);
    }

    public String emailDomain() {
        int at = email.indexOf('@');
        return at < 0 ? "" : email.substring(at + 1);
    }

    public boolean hasPassword() {
        return passwordHash != null && !passwordHash.isBlank();
    }

    public UserAccount verified(Instant now) {
        return new UserAccount(id, firstName, lastName, email, true, phone, passwordHash, enabled, smsConsent,
                termsAcceptedAt, activeOrgId, createdAt, now, version);
    }

    public UserAccount withPassword(String hash, Instant now) {
        return new UserAccount(id, firstName, lastName, email, emailVerified, phone, hash, enabled, smsConsent,
                termsAcceptedAt, activeOrgId, createdAt, now, version);
    }

    public UserAccount withEnabled(boolean value, Instant now) {
        return new UserAccount(id, firstName, lastName, email, emailVerified, phone, passwordHash, value, smsConsent,
                termsAcceptedAt, activeOrgId, createdAt, now, version);
    }

    public UserAccount withActiveOrg(String orgId, Instant now) {
        return new UserAccount(id, firstName, lastName, email, emailVerified, phone, passwordHash, enabled, smsConsent,
                termsAcceptedAt, orgId, createdAt, now, version);
    }

    /** Replaces the details of a pending (unverified) registration when the person signs up again. */
    public UserAccount restartedWith(String first, String last, String newPhone, String hash, boolean consent,
                                     Instant now) {
        return new UserAccount(id, first, last, email, false, newPhone, hash, enabled, consent, now, activeOrgId,
                createdAt, now, version);
    }
}
