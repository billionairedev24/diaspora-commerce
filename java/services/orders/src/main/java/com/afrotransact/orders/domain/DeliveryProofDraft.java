package com.afrotransact.orders.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What staff submit as proof of delivery, validated and normalised. Rules: 1 to 5 distinct photos (media asset ids,
 * UUIDs; the media service already refuses other file types and sizes over 10 MB for the proof-photo purpose and the
 * BFF checks each asset before calling), a recipient name up to 120 characters, a note up to 500, and coordinates
 * only as a pair within range (never required).
 */
public record DeliveryProofDraft(List<String> photoAssetIds, String recipientName, String note, Double latitude,
                                 Double longitude) {

    public static final int MAX_PHOTOS = 5;
    public static final int MAX_RECIPIENT = 120;
    public static final int MAX_NOTE = 500;
    private static final Pattern ASSET_ID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /** Validates and normalises (trimmed text, blank becomes null, lower-case ids, no duplicates). */
    public static DeliveryProofDraft check(List<String> photos, String recipientName, String note, Double latitude,
                                           Double longitude) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (photos != null) {
            for (String p : photos) {
                if (p == null || !ASSET_ID.matcher(p.trim()).matches()) {
                    throw new OrdersRuleViolation("orders.proof-photo-invalid",
                            "A proof photo must be a photo uploaded for delivery proof");
                }
                ids.add(p.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (ids.isEmpty()) {
            throw new OrdersRuleViolation("orders.proof-photo-required",
                    "Attach at least one photo as proof of delivery");
        }
        if (ids.size() > MAX_PHOTOS) {
            throw new OrdersRuleViolation("orders.proof-too-many-photos",
                    "Attach at most " + MAX_PHOTOS + " photos");
        }
        String name = clean(recipientName);
        if (name != null && name.length() > MAX_RECIPIENT) {
            throw new OrdersRuleViolation("orders.proof-recipient-too-long",
                    "The recipient's name can be up to " + MAX_RECIPIENT + " characters");
        }
        String text = clean(note);
        if (text != null && text.length() > MAX_NOTE) {
            throw new OrdersRuleViolation("orders.proof-note-too-long",
                    "The note can be up to " + MAX_NOTE + " characters");
        }
        if ((latitude == null) != (longitude == null)) {
            throw new OrdersRuleViolation("orders.proof-position-incomplete",
                    "Give both latitude and longitude, or neither");
        }
        if (latitude != null && (latitude.isNaN() || longitude.isNaN() || latitude < -90 || latitude > 90
                || longitude < -180 || longitude > 180)) {
            throw new OrdersRuleViolation("orders.proof-position-invalid", "That position is not on the map");
        }
        return new DeliveryProofDraft(new ArrayList<>(ids), name, text, latitude, longitude);
    }

    private static String clean(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip().replaceAll("\\p{Cntrl}", " ");
        return t.isEmpty() ? null : t;
    }
}
