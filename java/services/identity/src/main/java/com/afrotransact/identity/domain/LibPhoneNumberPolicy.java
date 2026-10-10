package com.afrotransact.identity.domain;

import java.util.Locale;
import java.util.Set;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;

/** Google libphonenumber implementation: per-country validation, mobile numbers only, stored in E.164. */
public class LibPhoneNumberPolicy implements PhoneNumberPolicy {

    /** Countries such as the US and Canada do not distinguish mobile from fixed-line ranges. */
    private static final Set<PhoneNumberType> MOBILE = Set.of(PhoneNumberType.MOBILE,
            PhoneNumberType.FIXED_LINE_OR_MOBILE);

    private final PhoneNumberUtil util = PhoneNumberUtil.getInstance();

    @Override
    public PhoneCheck check(String raw, String region) {
        if (raw == null || raw.isBlank()) {
            return PhoneCheck.invalid("Enter a mobile number");
        }
        String trimmed = raw.trim();
        String defaultRegion = region == null || region.isBlank() ? "ZZ" : region.trim().toUpperCase(Locale.ROOT);
        if (!trimmed.startsWith("+") && "ZZ".equals(defaultRegion)) {
            return PhoneCheck.invalid("Include the country code, for example +1 713 249 0142");
        }
        PhoneNumber number;
        try {
            number = util.parse(trimmed, defaultRegion);
        } catch (NumberParseException ex) {
            return PhoneCheck.invalid("That doesn't look like a phone number");
        }
        if (!util.isValidNumber(number)) {
            return PhoneCheck.invalid("That number isn't valid for the selected country");
        }
        if (!MOBILE.contains(util.getNumberType(number))) {
            return PhoneCheck.invalid("Use a mobile number so we can text you codes");
        }
        return PhoneCheck.ok(util.format(number, PhoneNumberFormat.E164), util.getRegionCodeForNumber(number));
    }
}
