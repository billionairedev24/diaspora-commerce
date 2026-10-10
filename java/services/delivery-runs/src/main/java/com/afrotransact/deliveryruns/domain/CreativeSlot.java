package com.afrotransact.deliveryruns.domain;

/** Advertorial image slots with their size spec; only the hero is required to publish. */
public enum CreativeSlot {
    HERO(1920, 640, true, "Home + pre-order page"),
    PDP(1200, 300, false, "Eligible product pages"),
    STORY(1080, 1920, false, "Instagram, TikTok, WhatsApp"),
    FEED(1080, 1080, false, "Instagram + Facebook feed"),
    EMAIL(1200, 400, false, "Announcement + reminders");

    /** A creative whose aspect ratio is more than this far off the spec is flagged (not blocked). */
    public static final double RATIO_TOLERANCE = 0.12;

    private final int width;
    private final int height;
    private final boolean required;
    private final String where;

    CreativeSlot(int width, int height, boolean required, String where) {
        this.width = width;
        this.height = height;
        this.required = required;
        this.where = where;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public boolean required() {
        return required;
    }

    public String where() {
        return where;
    }

    public double ratio() {
        return (double) width / height;
    }

    /** "1920 × 640". */
    public String spec() {
        return width + " × " + height;
    }

    /** {@code |w/h − spec| / spec > 12 %} (also true when the size is unknown). */
    public boolean ratioOff(int w, int h) {
        if (w <= 0 || h <= 0) {
            return true;
        }
        return Math.abs((double) w / h - ratio()) / ratio() > RATIO_TOLERANCE;
    }
}
