package com.afrotransact.bookings.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.afrotransact.bookings.domain.FeeSchedule;
import com.afrotransact.bookings.port.PaymentsClient;
import com.afrotransact.bookings.port.PaymentsClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class ProviderFeesImpl implements ProviderFees {

    private static final Logger LOG = LoggerFactory.getLogger(ProviderFeesImpl.class);
    /** A fallback is retried sooner than a real answer is refreshed. */
    private static final Duration FALLBACK_TTL = Duration.ofSeconds(30);

    private final PaymentsClient payments;
    private final BookingsSettings settings;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    ProviderFeesImpl(PaymentsClient payments, BookingsSettings settings, Clock clock) {
        this.payments = payments;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public FeeSchedule forOrg(String orgId) {
        Instant now = clock.instant();
        Cached hit = cache.get(orgId);
        if (hit != null && now.isBefore(hit.expiresAt())) {
            return hit.fees();
        }
        FeeSchedule fees;
        Duration ttl = settings.feesCacheTtl();
        try {
            PaymentsClient.EffectiveFees e = payments.effectiveFees(CATEGORY, orgId);
            fees = new FeeSchedule(e.commissionBps(), e.cardPctBps(), e.cardFixedMinor(), e.commissionSource(),
                    e.version());
        } catch (PaymentsClientException | IllegalArgumentException e) {
            FeeSchedule fallback = settings.fallbackFees();
            // AfroTransact Retail pays no commission even while payments can't be asked; only card fees apply.
            fees = settings.isRetail(orgId) ? new FeeSchedule(0, fallback.cardPctBps(), fallback.cardFixedMinor(),
                    FeeSchedule.RETAIL, null) : fallback;
            ttl = FALLBACK_TTL.compareTo(ttl) < 0 ? FALLBACK_TTL : ttl;
            LOG.warn("Fee schedule for {} unavailable from payments ({}); using configured fees "
                    + "(commission {} bps, card {} bps + {})", orgId, e.getMessage(), fees.commissionBps(),
                    fees.cardPctBps(), fees.cardFixedMinor());
        }
        cache.put(orgId, new Cached(fees, now.plus(ttl)));
        return fees;
    }

    private record Cached(FeeSchedule fees, Instant expiresAt) {
    }
}
