package com.afrotransact.fulfilment.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/** AfroTransact shipment label (Code 128): {@code ATS} + 9 upper-case base-36 characters, e.g. ATS7K2Q9H4M1. */
public final class PackageLabel {

    private static final Pattern FORMAT = Pattern.compile("ATS[0-9A-Z]{9}");

    private PackageLabel() {
    }

    public static boolean valid(String label) {
        return label != null && FORMAT.matcher(label).matches();
    }

    /** Normalises a scanned or typed label; throws when it is not an AfroTransact label. */
    public static String normalise(String raw) {
        String code = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!valid(code)) {
            throw new FulfilmentRuleViolation("fulfilment.label-invalid",
                    "Not an AfroTransact shipment label (ATS + 9 characters)");
        }
        return code;
    }
}
