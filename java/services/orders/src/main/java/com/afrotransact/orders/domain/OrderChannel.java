package com.afrotransact.orders.domain;

import java.util.Locale;

/**
 * Where an order came from (S272 attribution, last touch): the {@code ?src=} of a tracked link or the on-site
 * placement the shopper clicked; {@code AGENT_LATE} for customer-service late pre-order adds.
 */
public enum OrderChannel {
    SITE_HERO, SITE_RIBBON, SITE_PDP, SITE_PAGE, SITE_DIRECT, APP, INSTAGRAM, FACEBOOK, TIKTOK, WHATSAPP, EMAIL,
    SMS, AGENT_LATE;

    /** Maps the attribution source ({@code ig|fb|tt|wa|email|sms|app} or a placement) to a channel. */
    public static OrderChannel fromSource(String src) {
        String s = src == null ? "" : src.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "ig", "instagram" -> INSTAGRAM;
            case "fb", "facebook" -> FACEBOOK;
            case "tt", "tiktok" -> TIKTOK;
            case "wa", "whatsapp" -> WHATSAPP;
            case "email" -> EMAIL;
            case "sms" -> SMS;
            case "app" -> APP;
            case "hero", "site_hero" -> SITE_HERO;
            case "ribbon", "site_ribbon" -> SITE_RIBBON;
            case "pdp", "site_pdp" -> SITE_PDP;
            case "page", "site_page" -> SITE_PAGE;
            case "agent_late" -> AGENT_LATE;
            default -> SITE_DIRECT;
        };
    }
}
