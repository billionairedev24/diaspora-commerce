package com.afrotransact.taxreturns.application;

import java.time.LocalDate;
import java.util.List;

import com.afrotransact.taxreturns.domain.shadow.ShadowRow;
import com.afrotransact.taxreturns.domain.tax.LineDecision;
import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.domain.tax.TaxLineInput;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import com.afrotransact.taxreturns.port.ShadowQuoteRepository;

/**
 * SHADOW mode: the shadow provider is asked the same lines (with its mapped codes) next to the in-house engine, the two
 * answers are compared and stored, and the shadow figure is never charged and never blocks checkout.
 */
public interface TaxShadowService {

    /**
     * Compares in the background and returns at once. Best effort: a provider timeout, error or overload is counted and
     * dropped; it never throws and never delays the quote that triggered it.
     */
    void compare(Compare request);

    /** Disagreement report over a period (tax:read). */
    Report report(LocalDate from, LocalDate to, TaxProviderId provider, boolean onlyDifferences, int page, int size);

    /**
     * @param provider  the shadow provider
     * @param shipTo    delivery address
     * @param currency  currency
     * @param reference order reference
     * @param lines     the decided lines (including blocked ones, which are recorded as NO_DECISION)
     */
    record Compare(TaxProviderId provider, TaxAddress shipTo, String currency, String reference, List<Line> lines) {

        public Compare {
            lines = List.copyOf(lines);
        }
    }

    /**
     * @param input    the line with the provider codes of its outcome and the exempt flag
     * @param decision the stored decision
     */
    record Line(TaxLineInput input, LineDecision decision) {
    }

    /**
     * @param totals     compared lines, differing lines and the money difference
     * @param byCode     per internal tax code and provider code
     * @param byCategory per category
     * @param byOutcome  per in-house outcome
     * @param byState    per ship-to state
     * @param byKind     per kind of difference
     * @param differences the compared lines (only the differing ones unless asked)
     */
    record Report(LocalDate from, LocalDate to, TaxProviderId provider, ShadowQuoteRepository.Totals totals,
                  List<ShadowQuoteRepository.Group> byCode, List<ShadowQuoteRepository.Group> byCategory,
                  List<ShadowQuoteRepository.Group> byOutcome, List<ShadowQuoteRepository.Group> byState,
                  List<ShadowQuoteRepository.Group> byKind, List<ShadowRow> differences, int page, int size) {
    }
}
