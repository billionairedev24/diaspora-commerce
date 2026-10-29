package com.afrotransact.seller.application;

import java.util.List;

import com.afrotransact.seller.domain.FirstCharge;
import com.afrotransact.seller.domain.Plan;
import com.afrotransact.seller.domain.PlanQuote;

/**
 * A plan with its price for every cadence and the first-charge sentence per cadence.
 *
 * @param trialDays the trial a sign-up gets today: the plan's default or a running sign-up campaign's
 */
public record PlanOffer(Plan plan, List<PlanQuote> quotes, List<FirstCharge> firstCharges, int trialDays) {

    public PlanOffer {
        quotes = List.copyOf(quotes);
        firstCharges = List.copyOf(firstCharges);
    }
}
