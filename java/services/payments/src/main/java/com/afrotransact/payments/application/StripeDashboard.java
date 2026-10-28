package com.afrotransact.payments.application;

import java.util.Locale;

import com.afrotransact.payments.config.PaymentsProperties;
import org.springframework.stereotype.Component;

/**
 * Where to find a Stripe object in the Stripe dashboard: test mode for a test (or no) key, live mode for a live key.
 * Objects made by the local stand-in do not exist at Stripe and get no link.
 */
@Component
public class StripeDashboard {

    private static final String BASE = "https://dashboard.stripe.com/";

    private final boolean test;

    StripeDashboard(PaymentsProperties props) {
        String key = props.stripe() == null || props.stripe().secretKey() == null ? "" : props.stripe().secretKey();
        this.test = !(key.startsWith("sk_live_") || key.startsWith("rk_live_"));
    }

    /** The dashboard page of a Stripe id, or null for an id Stripe does not have. */
    public String url(String ref) {
        if (ref == null || ref.isBlank() || ref.contains("_localtest_")) {
            return null;
        }
        String mode = test ? "test/" : "";
        String id = ref.trim();
        return switch (id.substring(0, Math.min(id.length(), id.indexOf('_') < 0 ? id.length() : id.indexOf('_')))
                .toLowerCase(Locale.ROOT)) {
            case "tr" -> BASE + mode + "connect/transfers/" + id;
            case "acct" -> BASE + mode + "connect/accounts/" + id;
            case "pi", "ch", "py" -> BASE + mode + "payments/" + id;
            default -> BASE + mode + "search?query=" + id;
        };
    }
}
