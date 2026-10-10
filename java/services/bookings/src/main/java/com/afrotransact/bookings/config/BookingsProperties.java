package com.afrotransact.bookings.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code at.bookings.*}.
 *
 * @param holdTtl              slot hold while the customer authorises the deposit
 * @param requestSla           provider answer window for requests (design: 4 h)
 * @param autoCompleteAfter    confirmed bookings auto-complete this long after they end (design: 48 h)
 * @param lateProviderFeeMinor provider cancellation under 24 h (design: $15)
 * @param manageUrlBase        customer link to manage a booking
 * @param fees                 commission and card fees (mirror payments' "services" fee schedule)
 * @param payments             payments-service client
 * @param jobs                 background jobs
 */
@ConfigurationProperties(prefix = "at.bookings")
public record BookingsProperties(Duration holdTtl, Duration requestSla, Duration autoCompleteAfter,
                                 Long lateProviderFeeMinor, String manageUrlBase, Fees fees, Payments payments,
                                 Jobs jobs) {

    public BookingsProperties {
        holdTtl = holdTtl == null ? Duration.ofMinutes(15) : holdTtl;
        requestSla = requestSla == null ? Duration.ofHours(4) : requestSla;
        autoCompleteAfter = autoCompleteAfter == null ? Duration.ofHours(48) : autoCompleteAfter;
        lateProviderFeeMinor = lateProviderFeeMinor == null ? 1500L : lateProviderFeeMinor;
        manageUrlBase = manageUrlBase == null ? "https://afrotransact.com/account/appointments/" : manageUrlBase;
        fees = fees == null ? new Fees(null, null, null) : fees;
        payments = payments == null ? new Payments(null, null, null, null, null) : payments;
        jobs = jobs == null ? new Jobs(null) : jobs;
    }

    /** Fees used for take-home and earnings. */
    public record Fees(Integer commissionBps, Integer cardPctBps, Long cardFixedMinor) {

        public Fees {
            commissionBps = commissionBps == null ? 1000 : commissionBps;
            cardPctBps = cardPctBps == null ? 290 : cardPctBps;
            cardFixedMinor = cardFixedMinor == null ? 30L : cardFixedMinor;
        }
    }

    /**
     * payments-service and the client-credentials token used to call it.
     *
     * @param clientSecret blank = call payments without a token (local profile, security off)
     */
    public record Payments(String baseUrl, String tokenUri, String clientId, String clientSecret, Duration timeout) {

        public Payments {
            baseUrl = baseUrl == null ? "http://localhost:8105" : baseUrl;
            tokenUri = tokenUri == null ? "http://localhost:8100/oauth2/token" : tokenUri;
            clientId = clientId == null ? "bookings-service" : clientId;
            clientSecret = clientSecret == null ? "" : clientSecret;
            timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        }
    }

    /** Background jobs. */
    public record Jobs(Boolean enabled) {

        public Jobs {
            enabled = enabled == null ? Boolean.TRUE : enabled;
        }
    }
}
