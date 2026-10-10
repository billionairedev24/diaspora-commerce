package com.afrotransact.content.domain.site;

import java.util.Locale;
import java.util.regex.Pattern;

import com.afrotransact.content.domain.ContentRuleViolation;

/** Social networks shown in the footer and the mobile Help screen, each with its profile-URL rule. */
public enum SocialNetwork {
    TIKTOK("TikTok", "^https://(www\\.)?tiktok\\.com/@[\\w.]+/?$", "https://www.tiktok.com/@handle"),
    INSTAGRAM("Instagram", "^https://(www\\.)?instagram\\.com/[\\w.]+/?$", "https://www.instagram.com/handle"),
    FACEBOOK("Facebook", "^https://(www\\.)?facebook\\.com/[\\w.]+/?$", "https://www.facebook.com/page"),
    X("X", "^https://(www\\.)?(x|twitter)\\.com/\\w+/?$", "https://x.com/handle"),
    YOUTUBE("YouTube", "^https://(www\\.)?youtube\\.com/(@|c/|channel/)[\\w.-]+/?$", "https://www.youtube.com/@handle"),
    LINKEDIN("LinkedIn", "^https://(www\\.)?linkedin\\.com/company/[\\w-]+/?$", "https://www.linkedin.com/company/name"),
    WHATSAPP("WhatsApp channel", "^https://(www\\.)?whatsapp\\.com/channel/\\w+/?$", "https://www.whatsapp.com/channel/id");

    private final String label;
    private final Pattern profileUrl;
    private final String example;

    SocialNetwork(String label, String profileUrl, String example) {
        this.label = label;
        this.profileUrl = Pattern.compile(profileUrl, Pattern.CASE_INSENSITIVE);
        this.example = example;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String label() {
        return label;
    }

    public String example() {
        return example;
    }

    /** True when {@code url} is a full https:// profile URL on this network. */
    public boolean accepts(String url) {
        return url != null && profileUrl.matcher(url.trim()).matches();
    }

    public static SocialNetwork fromKey(String key) {
        for (SocialNetwork n : values()) {
            if (n.key().equalsIgnoreCase(key) || n.name().equalsIgnoreCase(key)) {
                return n;
            }
        }
        throw new ContentRuleViolation("content.unknown-network", "Unknown social network " + key);
    }
}
