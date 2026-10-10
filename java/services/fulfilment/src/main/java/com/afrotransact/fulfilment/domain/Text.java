package com.afrotransact.fulfilment.domain;

/** Small text helpers shared by the domain rules. */
final class Text {

    private Text() {
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    static boolean shorterThan(String s, int min) {
        return s == null || s.trim().length() < min;
    }
}
