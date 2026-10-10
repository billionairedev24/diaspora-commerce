package com.afrotransact.payments.domain;

/** Side of a journal line. */
public enum EntrySide {
    DEBIT("D"),
    CREDIT("C");

    private final String code;

    EntrySide(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public EntrySide opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }

    public static EntrySide ofCode(String code) {
        return "D".equals(code) ? DEBIT : CREDIT;
    }
}
