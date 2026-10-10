package com.afrotransact.identity.domain;

/** Validates and normalises mobile phone numbers. */
public interface PhoneNumberPolicy {

    /**
     * @param raw    the number as typed, in E.164 ({@code +1...}) or national format
     * @param region ISO 3166 region from the country picker, used when {@code raw} has no leading {@code +}
     */
    PhoneCheck check(String raw, String region);
}
