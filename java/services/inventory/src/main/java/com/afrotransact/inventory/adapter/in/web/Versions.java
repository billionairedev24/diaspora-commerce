package com.afrotransact.inventory.adapter.in.web;

/** If-Match parsing: the ETag is the quoted version, e.g. {@code "3"}; absent or {@code *} skips the check. */
final class Versions {

    private Versions() {
    }

    static Long parse(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank() || "*".equals(ifMatch.trim())) {
            return null;
        }
        String v = ifMatch.trim();
        v = (v.startsWith("W/") ? v.substring(2) : v).replace("\"", "");
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("If-Match must be the version from the ETag, e.g. \"3\"", e);
        }
    }
}
