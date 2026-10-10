package com.afrotransact.deliveryruns.domain;

import java.util.Locale;

/**
 * Where a pre-order came from: {@code ?src=} of advertorial links (ig, fb, tt, wa, email, sms, app) or the on-site
 * placement clicked (hero, ribbon, pdp, page). Last touch wins; no touch is {@link #SITE_DIRECT}.
 */
public enum AttributionChannel {
    SITE_HERO("hero", "Site · hero"),
    SITE_RIBBON("ribbon", "Site · ribbon"),
    SITE_PDP("pdp", "Site · product page"),
    SITE_PAGE("page", "Site · pre-order page"),
    SITE_DIRECT("direct", "Site · direct"),
    APP("app", "Mobile app"),
    INSTAGRAM("ig", "Instagram"),
    FACEBOOK("fb", "Facebook"),
    TIKTOK("tt", "TikTok"),
    WHATSAPP("wa", "WhatsApp"),
    EMAIL("email", "Email"),
    SMS("sms", "SMS"),
    AGENT_LATE("agent", "Agent (late)");

    private final String source;
    private final String label;

    AttributionChannel(String source, String label) {
        this.source = source;
        this.label = label;
    }

    /** The {@code src=} value. */
    public String source() {
        return source;
    }

    /** Display name in the back office and exports. */
    public String label() {
        return label;
    }

    /** From a {@code src=} value or a channel name; anything else is a direct visit. */
    public static AttributionChannel fromSource(String src) {
        if (src == null || src.isBlank()) {
            return SITE_DIRECT;
        }
        String s = src.trim().toLowerCase(Locale.ROOT);
        for (AttributionChannel c : values()) {
            if (c.source.equals(s) || c.name().toLowerCase(Locale.ROOT).equals(s)) {
                return c;
            }
        }
        return SITE_DIRECT;
    }

    /** {@code src=ig&cr=story&po=LBK-7}: the tracking tag of an order. */
    public static String trackingTag(String src, String creative, String windowCode) {
        StringBuilder b = new StringBuilder("src=").append(src == null || src.isBlank() ? "direct" : src.trim());
        if (creative != null && !creative.isBlank()) {
            b.append("&cr=").append(creative.trim());
        }
        if (windowCode != null && !windowCode.isBlank()) {
            b.append("&po=").append(windowCode.trim());
        }
        return b.toString();
    }
}
