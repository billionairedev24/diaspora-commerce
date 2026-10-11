package com.afrotransact.identity.adapter.in.web;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.google.i18n.phonenumbers.PhoneNumberUtil;

/** Country picker of the sign-up form: every region libphonenumber knows, United States first, then A–Z. */
final class PhoneCountries {

    static final String DEFAULT_REGION = "US";
    private static final int REGIONAL_INDICATOR_OFFSET = 127397;
    private static final List<Country> ALL = build();

    private PhoneCountries() {
    }

    /** One option: ISO region code, country name and label, for example {@code 🇺🇸 +1 United States}. */
    record Country(String code, String name, String label) {
    }

    static List<Country> all() {
        return ALL;
    }

    static boolean known(String code) {
        return code != null && ALL.stream().anyMatch(c -> c.code().equals(code));
    }

    private static List<Country> build() {
        PhoneNumberUtil util = PhoneNumberUtil.getInstance();
        List<Country> out = new ArrayList<>();
        for (String region : util.getSupportedRegions()) {
            String name = Locale.of("", region).getDisplayCountry(Locale.ENGLISH);
            out.add(new Country(region, name, flag(region) + " +" + util.getCountryCodeForRegion(region) + " " + name));
        }
        out.sort(Comparator.comparing((Country c) -> !DEFAULT_REGION.equals(c.code()))
                .thenComparing(Country::name));
        return List.copyOf(out);
    }

    private static String flag(String region) {
        StringBuilder b = new StringBuilder();
        region.toUpperCase(Locale.ROOT).chars().forEach(c -> b.appendCodePoint(REGIONAL_INDICATOR_OFFSET + c));
        return b.toString();
    }
}
