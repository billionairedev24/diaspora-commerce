package com.afrotransact.seller.domain;

import java.util.Optional;

/** Phone-number parsing and validation (implemented with libphonenumber in an adapter). */
public interface PhoneNumbers {

    /**
     * Parses {@code raw} (national format for {@code defaultRegion}, or international) and returns it in E.164 when it
     * is a valid mobile (or fixed-line-or-mobile) number.
     */
    Optional<String> toE164Mobile(String raw, String defaultRegion);
}
