package com.afrotransact.deliveryruns.domain;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The UTM tags every pre-order announcement link carries (S262), in the style of the delivery run links: {@code
 * utm_source} and {@code utm_medium} are the channel the message went out on (email, sms, push),
 * {@code utm_campaign} is {@code preorder-<window code>} and {@code utm_content} is what the message is (announce,
 * waitlist, remind, last, closed, ofd). The same slot is the {@code cr=} of the order attribution
 * ({@code src=<email|sms|app>&cr=<slot>&po=<code>}), so an order placed from the link is credited to the same
 * campaign and message in the staff analytics.
 */
public final class PreorderUtm {

    /** The messages a window sends, by the slot their links carry. */
    public static final Map<CommKind, String> SLOTS = Map.of(CommKind.ANNOUNCE, "announce",
            CommKind.REMIND_48H, "remind", CommKind.LAST_CALL_6H, "last", CommKind.CLOSED, "closed",
            CommKind.OUT_FOR_DELIVERY, "ofd");
    /** The extra slot of the waitlist's "pre-order is open" email (an ANNOUNCE variant). */
    public static final String WAITLIST = "waitlist";

    private static final Map<String, String> CHANNEL_OF_SRC = Map.of("email", "email", "sms", "sms", "app", "push");

    private PreorderUtm() {
    }

    /**
     * @param source   utm_source: the channel
     * @param medium   utm_medium: the channel
     * @param campaign utm_campaign: {@code preorder-<code>}
     * @param content  utm_content: the message slot
     */
    public record Tags(String source, String medium, String campaign, String content) {
    }

    /** The tags of a message of {@code slot} sent on {@code channel} (email, sms, push) for the window. */
    public static Tags tags(String channel, String windowCode, String slot) {
        return new Tags(channel, channel, "preorder-" + windowCode, slot);
    }

    /**
     * The query of an announcement link: the order attribution the storefront keeps until checkout ({@code src},
     * {@code po}, {@code cr} = the message slot) plus the UTM of the click.
     */
    public static String query(String channel, String windowCode, String slot) {
        Tags t = tags(channel, windowCode, slot);
        return "src=" + encode(src(channel)) + "&po=" + encode(windowCode) + "&cr=" + encode(slot) + "&"
                + utm(t.source(), t.medium(), t.campaign(), t.content());
    }

    /** The query of a social share link: {@code src} (ig, fb, tt, wa) and {@code cr} as the order attribution. */
    public static String socialQuery(String network, String src, String windowCode, String creative) {
        return "src=" + encode(src) + "&po=" + encode(windowCode) + "&cr=" + encode(creative) + "&"
                + utm(network, "social", "preorder-" + windowCode, creative);
    }

    private static String utm(String source, String medium, String campaign, String content) {
        return "utm_source=" + encode(source) + "&utm_medium=" + encode(medium) + "&utm_campaign=" + encode(campaign)
                + "&utm_content=" + encode(content);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** The {@code src=} order attribution of a channel: email, sms, and app for push. */
    public static String src(String channel) {
        return "push".equals(channel) ? "app" : channel;
    }

    /**
     * The campaign an order was credited to, from its tracking tag ({@code src=email&cr=announce&po=LUB-7}): only
     * orders that came from one of the window's own messages (email, sms or push, with a message slot).
     */
    public static Optional<Tags> ofTrackingTag(String trackingTag, String windowCode) {
        if (trackingTag == null || trackingTag.isBlank()) {
            return Optional.empty();
        }
        String src = null;
        String slot = null;
        for (String part : trackingTag.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                String key = part.substring(0, eq);
                String value = part.substring(eq + 1).toLowerCase(Locale.ROOT);
                if (key.equals("src")) {
                    src = value;
                } else if (key.equals("cr")) {
                    slot = value;
                }
            }
        }
        String channel = src == null ? null : CHANNEL_OF_SRC.get(src);
        boolean message = slot != null && (WAITLIST.equals(slot) || SLOTS.containsValue(slot));
        return channel != null && message ? Optional.of(tags(channel, windowCode, slot)) : Optional.empty();
    }
}
