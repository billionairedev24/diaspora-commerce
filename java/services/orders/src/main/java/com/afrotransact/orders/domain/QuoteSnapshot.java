package com.afrotransact.orders.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * What a quote is for: the cart lines, the packages they form and the address. Only city, state and ZIP are kept
 * (staff price the distance from them); the full address is matched through {@link #shipToKey}, a hash.
 *
 * @param cartHash  hash of the lines (variant, seller, quantity summed per variant and seller); prices and packaging
 *                  are not part of it, so the same hash is computed from the cart and from the placed order
 * @param shipToKey hash of the normalised address; a quote is void for any other address
 * @param customerName the name on the address, so staff address the shopper by name
 */
public record QuoteSnapshot(String cartHash, String shipToKey, String currency, List<QuotePack> packages, List<QuoteLine> lines,
                            Place place, String customerName, QuoteContact contact, String note) {

    public QuoteSnapshot {
        packages = packages == null ? List.of() : List.copyOf(packages);
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** One package of the cart: a seller and how it is fulfilled. */
    public record QuotePack(String packageId, String sellerName, String fulfilment, int itemCount) {
    }

    /** One cart line. */
    public record QuoteLine(String variantAtin, String title, String packageId, int quantity, long unitPriceMinor) {
    }

    /** Where the delivery goes, as far as staff need. */
    public record Place(String city, String state, String postalCode, String country) {

        public String summary() {
            String c = city == null ? "" : city;
            return (c + (state == null || state.isBlank() ? "" : ", " + state)
                    + (postalCode == null || postalCode.isBlank() ? "" : " " + postalCode)).trim();
        }
    }

    /** Hash of the cart lines; the same lines in any order give the same hash. */
    public static String hashLines(List<LineKey> keys) {
        StringBuilder sb = new StringBuilder();
        keys.stream().sorted(Comparator.comparing(LineKey::variantAtin).thenComparing(LineKey::sellerOrgId))
                .forEach(k -> sb.append(k.variantAtin()).append('|').append(k.sellerOrgId()).append('|')
                        .append(k.quantity()).append(';'));
        return sha256(sb.toString());
    }

    /** The identity of a line for the cart hash. */
    public record LineKey(String variantAtin, String sellerOrgId, int quantity) {
    }

    /** Hash of the delivery address: lines 1 and 2, city, state, ZIP and country, case and spacing ignored. */
    public static String hashAddress(ShipTo to) {
        return sha256(String.join("|", norm(to.line1()), norm(to.line2()), norm(to.city()), norm(to.state()),
                norm(zip5(to.postalCode())), norm(to.country() == null ? "US" : to.country())));
    }

    /** The shopper-and-cart-and-address key that makes a repeated request return the same case. */
    public static String dedupeKey(String cartHash, String shipToKey) {
        return sha256(cartHash + "#" + shipToKey);
    }

    private static String zip5(String zip) {
        String z = zip == null ? "" : zip.trim();
        return z.length() > 5 && z.charAt(5) == '-' ? z.substring(0, 5) : z;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
