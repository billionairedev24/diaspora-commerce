package com.afrotransact.seller.adapter.out.phone;

import java.util.Locale;
import java.util.Optional;

import com.afrotransact.seller.domain.PhoneNumbers;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

/** libphonenumber: parses national or international input and accepts mobile numbers only. */
public class LibPhoneNumbers implements PhoneNumbers {

    private final PhoneNumberUtil util = PhoneNumberUtil.getInstance();

    @Override
    public Optional<String> toE164Mobile(String raw, String defaultRegion) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            Phonenumber.PhoneNumber n = util.parse(raw.trim(), defaultRegion == null ? "US"
                    : defaultRegion.toUpperCase(Locale.ROOT));
            if (!util.isValidNumber(n)) {
                return Optional.empty();
            }
            PhoneNumberUtil.PhoneNumberType type = util.getNumberType(n);
            if (type != PhoneNumberUtil.PhoneNumberType.MOBILE
                    && type != PhoneNumberUtil.PhoneNumberType.FIXED_LINE_OR_MOBILE) {
                return Optional.empty();
            }
            return Optional.of(util.format(n, PhoneNumberUtil.PhoneNumberFormat.E164));
        } catch (NumberParseException e) {
            return Optional.empty();
        }
    }
}
