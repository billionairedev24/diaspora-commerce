package com.afrotransact.identity.domain;

/**
 * Result of validating a phone number.
 *
 * @param valid  true when the number is a real mobile number for its country
 * @param e164   normalised E.164 form (for example {@code +17132490142}) when valid
 * @param region ISO 3166 region of the number when known
 * @param error  human-readable reason when invalid
 */
public record PhoneCheck(boolean valid, String e164, String region, String error) {

    public static PhoneCheck ok(String e164, String region) {
        return new PhoneCheck(true, e164, region, null);
    }

    public static PhoneCheck invalid(String error) {
        return new PhoneCheck(false, null, null, error);
    }
}
