package com.afrotransact.payments.application;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

import com.afrotransact.payments.config.PaymentsProperties;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.PayoutPolicy;
import org.springframework.stereotype.Service;

@Service
class SettingsSummaryServiceImpl implements SettingsSummaryService {

    private final PaymentsSettings settings;
    private final PaymentsProperties props;
    private final PayoutPolicy payoutPolicy;
    private final PaymentsAccess access;

    SettingsSummaryServiceImpl(PaymentsSettings settings, PaymentsProperties props, PayoutPolicy payoutPolicy,
                               PaymentsAccess access) {
        this.settings = settings;
        this.props = props;
        this.payoutPolicy = payoutPolicy;
        this.access = access;
    }

    @Override
    public List<SettingLine> summary() {
        access.reader();
        String key = props.stripe() == null || props.stripe().secretKey() == null ? "" : props.stripe().secretKey();
        String stripeMode = key.startsWith("sk_live_") || key.startsWith("rk_live_") ? "Live (real money)"
                : key.isBlank() || key.contains("replace_with") ? "Local stand-in (nothing reaches Stripe)"
                : "Test (Stripe test mode)";
        Money minimum = payoutPolicy.minimum(settings.currency());
        return List.of(
                new SettingLine("stripe.mode", "Stripe", stripeMode,
                        "Which Stripe account money moves through. Test mode never moves real money.",
                        "STRIPE_SECRET_KEY"),
                new SettingLine("settlement.hold", "Hold before a seller can be paid",
                        days(settings.settlementHold()) + " after delivery",
                        "A seller's net for an order is held until this long after it is delivered (service "
                                + "bookings: after completion), then transferred to their Stripe account. It protects "
                                + "against returns and disputes.", "SETTLEMENT_HOLD"),
                new SettingLine("settlement.recheck", "Retry of a blocked transfer", hours(settings.retryRecheck()),
                        "When a transfer cannot be made (no Stripe account yet, Stripe refused it) it is tried again "
                                + "after this long.", "SETTLEMENT_RECHECK"),
                new SettingLine("payout.minimum", "Smallest payout a seller can request", money(minimum),
                        "A seller asks for a payout from what is available; requests below this are refused.",
                        "PAYOUT_MINIMUM_MINOR"),
                new SettingLine("payout.daily", "Payout requests", "One per seller per day (" + settings.payoutZone()
                        + " time), then approved by Finance",
                        "Each request is approved, held or released by Finance staff in Payouts & ledger.",
                        "built in"),
                new SettingLine("currency", "Currency", settings.currency(), "The currency of balances and payouts.",
                        "built in"),
                new SettingLine("retail.org", "AfroTransact Retail", settings.retailOrgId(),
                        "AfroTransact's own first-party store. Its sales are AfroTransact revenue: never held, "
                                + "transferred or paid out.", "RETAIL_ORG_ID"));
    }

    private static String days(Duration d) {
        long days = d.toDays();
        return days >= 1 && d.equals(Duration.ofDays(days)) ? days + (days == 1 ? " day" : " days") : hours(d);
    }

    private static String hours(Duration d) {
        long minutes = d.toMinutes();
        if (minutes < 60) {
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        long h = d.toHours();
        return h + (h == 1 ? " hour" : " hours");
    }

    private static String money(Money m) {
        return String.format(Locale.ROOT, "$%d.%02d", m.minor() / 100, Math.abs(m.minor() % 100));
    }
}
