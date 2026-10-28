package com.afrotransact.deliveryruns.application;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.afrotransact.deliveryruns.domain.Channel;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.Run;
import org.springframework.stereotype.Component;

/**
 * Storefront links (built from {@code PUBLIC_WEB_URL}, never a hard-coded host) with UTM attribution, and the display
 * strings messages use. UTM: {@code utm_source} = channel or network, {@code utm_medium} = email | sms | push |
 * whatsapp | social | advertorial, {@code utm_campaign} = {@code delivery-run-<slug>-<yyyymmdd>}, {@code utm_content}
 * = what the message is about (opened, cutoff_reminder, ...).
 */
@Component
class RunLinks {

    private static final DateTimeFormatter CAMPAIGN_DATE = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    private static final DateTimeFormatter CUTOFF = DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a z", Locale.US);

    private final DeliveryRunsSettings settings;

    RunLinks(DeliveryRunsSettings settings) {
        this.settings = settings;
    }

    String locationUrl(Location location) {
        return settings.publicWebUrl() + settings.locationPath() + "/" + location.slug();
    }

    String runUrl(Location location, Run run) {
        return locationUrl(location) + "?run=" + run.id();
    }

    /** Run link attributed to a source / medium. */
    String runUrl(Location location, Run run, String source, String medium, String content) {
        return runUrl(location, run) + "&" + utm(source, medium, campaign(location, run), content);
    }

    /**
     * The run link from an advertorial: {@code src=page&cr=<creative>} is the order attribution the storefront keeps
     * until checkout (so the advertorial is credited with the run's order), plus the UTM of the click.
     */
    String runAdvertorialUrl(Location location, Run run, String creative) {
        return runUrl(location, run) + "&src=page&cr=" + encode(creative) + "&"
                + utm("storefront", "advertorial", campaign(location, run), creative);
    }

    /** One attributed run link per announcement channel, plus {@code social} for shares. */
    Map<String, String> channelLinks(Location location, Run run, String content) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Channel c : Channel.values()) {
            String name = c.name().toLowerCase(Locale.ROOT);
            out.put(name, runUrl(location, run, name, name, content));
        }
        out.put("social", runUrl(location, run, "share", "social", content));
        return out;
    }

    /** Share intents for the location page (Facebook, X, WhatsApp), each attributed to its network. */
    Map<String, String> shareLinks(Location location, String text) {
        String base = locationUrl(location) + "?";
        String campaign = "delivery-run-" + location.slug();
        String fb = base + utm("facebook", "social", campaign, "share");
        String x = base + utm("x", "social", campaign, "share");
        String wa = base + utm("whatsapp", "social", campaign, "share");
        Map<String, String> out = new LinkedHashMap<>();
        out.put("facebook", "https://www.facebook.com/sharer/sharer.php?u=" + encode(fb));
        out.put("x", "https://twitter.com/intent/tweet?text=" + encode(text) + "&url=" + encode(x));
        out.put("whatsapp", "https://wa.me/?text=" + encode(text + " " + wa));
        return out;
    }

    /** The storefront's pre-order page. */
    String windowUrl() {
        return settings.publicWebUrl() + "/pre-orders";
    }

    /**
     * The pre-order page from an advertorial: {@code src=page&cr=<creative>&po=<window code>} is the order attribution
     * the storefront keeps until checkout (so the advertorial is credited with the order), plus the UTM of the click.
     */
    String windowAdvertorialUrl(String windowCode, String creative) {
        return windowUrl() + "?src=page&cr=" + encode(creative) + "&po=" + encode(windowCode) + "&"
                + utm("storefront", "advertorial", "preorder-" + windowCode, creative);
    }

    String confirmUrl(String token) {
        return settings.publicWebUrl() + settings.locationPath() + "/subscriptions/confirm?token=" + encode(token);
    }

    String unsubscribeUrl(String token) {
        return settings.publicWebUrl() + settings.locationPath() + "/subscriptions/unsubscribe?token=" + encode(token);
    }

    String campaign(Location location, Run run) {
        ZoneId zone = location.details().timeZone();
        return "delivery-run-" + location.slug() + "-" + CAMPAIGN_DATE.format(run.terms().window().start().atZone(zone));
    }

    /** "Saturday, October 24" in the location's zone. */
    String deliveryDay(Location location, Run run) {
        return DAY.format(run.terms().window().start().atZone(location.details().timeZone()));
    }

    /** "10:00 AM – 2:00 PM". */
    String windowDisplay(Location location, Run run) {
        ZoneId zone = location.details().timeZone();
        return TIME.format(run.terms().window().start().atZone(zone)) + " – "
                + TIME.format(run.terms().window().end().atZone(zone));
    }

    /** "Thu, Oct 22 · 8:00 PM CDT". */
    String cutoffDisplay(Location location, Run run) {
        return CUTOFF.format(run.terms().cutoffAt().atZone(location.details().timeZone()));
    }

    /** "$7.99". */
    static String money(long minor, String currency) {
        Currency c = Currency.getInstance(currency);
        NumberFormat f = NumberFormat.getCurrencyInstance(Locale.US);
        f.setCurrency(c);
        int digits = Math.max(0, c.getDefaultFractionDigits());
        f.setMinimumFractionDigits(digits);
        f.setMaximumFractionDigits(digits);
        return f.format(BigDecimal.valueOf(minor, digits));
    }

    private static String utm(String source, String medium, String campaign, String content) {
        return "utm_source=" + encode(source) + "&utm_medium=" + encode(medium) + "&utm_campaign=" + encode(campaign)
                + "&utm_content=" + encode(content);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
