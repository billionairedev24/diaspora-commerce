package com.afrotransact.retail.domain;

import java.util.regex.Pattern;

/** Batch number format shared by ASN lines and receipts: 4–20 letters, digits or dashes (design validateReceipt). */
public final class LotRules {

    public static final Pattern LOT_NO = Pattern.compile("[A-Z0-9-]{4,20}");
    public static final Pattern BIN = Pattern.compile("[A-Z]-\\d{2}-\\d{2}");

    private LotRules() {
    }

    public static boolean validLot(String lotNo) {
        return lotNo != null && LOT_NO.matcher(lotNo).matches();
    }
}
