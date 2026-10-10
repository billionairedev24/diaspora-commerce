package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.OrgKind;
import com.afrotransact.seller.domain.PlanPricing;
import com.afrotransact.seller.port.PlanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PlanServiceImpl implements PlanService {

    private final PlanRepository plans;
    private final Clock clock;

    PlanServiceImpl(PlanRepository plans, Clock clock) {
        this.plans = plans;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PlanOffer> list(OrgKind kind) {
        LocalDate today = LocalDate.now(clock);
        return plans.findAll(kind).stream().map(p -> new PlanOffer(p,
                Arrays.stream(Cadence.values()).map(c -> PlanPricing.quote(p, c)).toList(),
                Arrays.stream(Cadence.values()).map(c -> PlanPricing.firstCharge(p, c, today)).toList())).toList();
    }
}
