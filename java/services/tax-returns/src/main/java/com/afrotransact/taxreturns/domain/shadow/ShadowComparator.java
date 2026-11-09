package com.afrotransact.taxreturns.domain.shadow;

import java.math.BigDecimal;
import java.time.Instant;

import com.afrotransact.taxreturns.domain.profile.Outcome;
import com.afrotransact.taxreturns.domain.tax.TaxLineInput;
import com.afrotransact.taxreturns.domain.tax.TaxLineResult;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;

/** Compares the in-house result of a line with a provider's. Pure. */
public final class ShadowComparator {

    private static final BigDecimal RATE_TOLERANCE = new BigDecimal("0.00005");

    private ShadowComparator() {
    }

    /**
     * @param inHouse  the in-house result for the line
     * @param provider the provider's result, or null when it could not answer
     * @param error    why it could not answer
     */
    public static ShadowRow compare(Instant at, String orderRef, String state, String category, String currency,
                                    TaxProviderId id, TaxLineInput line, TaxLineResult inHouse, TaxLineResult provider,
                                    String error) {
        Outcome ours = line.exempt() ? Outcome.EXEMPT : Outcome.TAXABLE;
        String code = line.codeFor(id);
        if (provider == null) {
            return new ShadowRow(at, orderRef, line.lineId(), line.atin(), id, state, category, line.taxCode(), code,
                    ShadowKind.PROVIDER_ERROR, ours, rateOf(inHouse), taxOf(inHouse), null, null, null, error, 0L,
                    currency);
        }
        Outcome theirs = provider.taxMinor() > 0 ? Outcome.TAXABLE : Outcome.EXEMPT;
        long ourTax = inHouse == null ? 0L : inHouse.taxMinor();
        long diff = provider.taxMinor() - ourTax;
        ShadowKind kind;
        if (ours != theirs) {
            kind = ShadowKind.OUTCOME;
        } else if (diff != 0 || rateDiffers(inHouse, provider)) {
            kind = ShadowKind.RATE;
        } else {
            kind = ShadowKind.MATCH;
        }
        return new ShadowRow(at, orderRef, line.lineId(), line.atin(), id, state, category, line.taxCode(), code, kind,
                ours, rateOf(inHouse), taxOf(inHouse), theirs, provider.rate(), provider.taxMinor(), null, diff,
                currency);
    }

    /** A line the in-house engine could not decide: recorded, nothing to compare. */
    public static ShadowRow noDecision(Instant at, String orderRef, String state, String category, String currency,
                                       TaxProviderId id, String lineId, String atin) {
        return new ShadowRow(at, orderRef, lineId, atin, id, state, category, null, null, ShadowKind.NO_DECISION, null,
                null, null, null, null, null, null, 0L, currency);
    }

    private static boolean rateDiffers(TaxLineResult a, TaxLineResult b) {
        if (a == null || a.rate() == null || b.rate() == null) {
            return false;
        }
        return a.rate().subtract(b.rate()).abs().compareTo(RATE_TOLERANCE) > 0;
    }

    private static BigDecimal rateOf(TaxLineResult r) {
        return r == null ? null : r.rate();
    }

    private static Long taxOf(TaxLineResult r) {
        return r == null ? null : r.taxMinor();
    }
}
