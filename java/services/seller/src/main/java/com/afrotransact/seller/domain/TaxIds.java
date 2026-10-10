package com.afrotransact.seller.domain;

import java.util.Optional;
import java.util.Set;

/**
 * US tax identification numbers. Applicants type digits with or without dashes; the server strips everything but
 * digits, checks the structure and formats SSN/ITIN as {@code 123-45-6789} and EIN as {@code 12-3456789}.
 */
public final class TaxIds {

    /** EIN prefixes the IRS has never assigned. */
    private static final Set<String> INVALID_EIN_PREFIXES = Set.of("00", "07", "08", "09", "17", "18", "19", "28",
            "29", "49", "69", "70", "78", "79", "89", "96", "97");

    private TaxIds() {
    }

    public static String digits(String raw) {
        return raw == null ? "" : raw.replaceAll("[^0-9]", "");
    }

    /** SSN or ITIN (an ITIN starts with 9 and has a group of 50–65, 70–88, 90–92 or 94–99). */
    public static Optional<TaxIdType> individualType(String raw) {
        String d = digits(raw);
        if (d.length() != 9 || raw.replaceAll("[0-9\\-\\s]", "").length() > 0) {
            return Optional.empty();
        }
        String area = d.substring(0, 3);
        int group = Integer.parseInt(d.substring(3, 5));
        String serial = d.substring(5);
        if (d.charAt(0) == '9') {
            boolean itinGroup = (group >= 50 && group <= 65) || (group >= 70 && group <= 88)
                    || (group >= 90 && group <= 92) || (group >= 94 && group <= 99);
            return itinGroup ? Optional.of(TaxIdType.ITIN) : Optional.empty();
        }
        if ("000".equals(area) || "666".equals(area) || group == 0 || "0000".equals(serial)) {
            return Optional.empty();
        }
        return Optional.of(TaxIdType.SSN);
    }

    public static boolean isValidEin(String raw) {
        String d = digits(raw);
        return d.length() == 9 && raw.replaceAll("[0-9\\-\\s]", "").isEmpty()
                && !INVALID_EIN_PREFIXES.contains(d.substring(0, 2));
    }

    public static String format(TaxIdType type, String raw) {
        String d = digits(raw);
        if (d.length() != 9) {
            throw new IllegalArgumentException("A tax ID has 9 digits");
        }
        return type == TaxIdType.EIN
                ? d.substring(0, 2) + "-" + d.substring(2)
                : d.substring(0, 3) + "-" + d.substring(3, 5) + "-" + d.substring(5);
    }

    public static String last4(String raw) {
        String d = digits(raw);
        return d.length() < 4 ? d : d.substring(d.length() - 4);
    }

    /** Masked for reviewers without PII access: {@code •••-••-6789} or {@code ••-•••6789}. */
    public static String masked(TaxIdType type, String last4) {
        if (last4 == null) {
            return null;
        }
        return type == TaxIdType.EIN ? "••-•••" + last4 : "•••-••-" + last4;
    }
}
