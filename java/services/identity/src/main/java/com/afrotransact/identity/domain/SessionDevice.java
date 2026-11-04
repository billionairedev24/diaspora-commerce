package com.afrotransact.identity.domain;

/**
 * The browser or app a sign-in came from, as the staff console's session list shows it: the User-Agent (cut to 255
 * characters) and a SHA-256 hash of the IP address (the address itself is not kept here).
 */
public record SessionDevice(String userAgent, String ipHash) {

    /** Request attribute under which the login-ticket filter hands the ticket's device to the authorization store. */
    public static final String REQUEST_ATTRIBUTE = SessionDevice.class.getName();
    public static final int MAX_USER_AGENT = 255;

    /** A device from the raw request values; null when there is neither a User-Agent nor an address. */
    public static SessionDevice of(String userAgent, String ip) {
        String ua = userAgent == null || userAgent.isBlank() ? null : truncate(userAgent.strip());
        String hash = ip == null || ip.isBlank() ? null : SecretHashing.sha256Hex(ip.strip());
        return ua == null && hash == null ? null : new SessionDevice(ua, hash);
    }

    /** A device read back from storage (the address is already a hash); null when nothing was recorded. */
    public static SessionDevice stored(String userAgent, String ipHash) {
        return userAgent == null && ipHash == null ? null : new SessionDevice(userAgent, ipHash);
    }

    private static String truncate(String s) {
        return s.length() <= MAX_USER_AGENT ? s : s.substring(0, MAX_USER_AGENT);
    }
}
