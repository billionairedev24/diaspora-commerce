package com.afrotransact.seller.application;

import java.util.List;

import com.afrotransact.seller.domain.FirstCharge;
import com.afrotransact.seller.domain.Plan;
import com.afrotransact.seller.domain.PlanQuote;

/** A plan with its price for every cadence and the first-charge sentence per cadence. */
public record PlanOffer(Plan plan, List<PlanQuote> quotes, List<FirstCharge> firstCharges) {

    public PlanOffer {
        quotes = List.copyOf(quotes);
        firstCharges = List.copyOf(firstCharges);
    }
}
