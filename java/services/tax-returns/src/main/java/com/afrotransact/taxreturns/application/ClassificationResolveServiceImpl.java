package com.afrotransact.taxreturns.application;

import java.util.List;
import java.util.Map;
import java.util.Locale;

import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.domain.profile.Resolution;
import com.afrotransact.taxreturns.domain.profile.ResolveStatus;
import com.afrotransact.taxreturns.port.TaxTelemetry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ClassificationResolveServiceImpl implements ClassificationResolveService {

    private final DecisionEngine engine;
    private final TaxReturnsProperties props;
    private final TaxTelemetry telemetry;
    private final Access access;

    ClassificationResolveServiceImpl(DecisionEngine engine, TaxReturnsProperties props, TaxTelemetry telemetry,
                                     Access access) {
        this.engine = engine;
        this.props = props;
        this.telemetry = telemetry;
        this.access = access;
    }

    @Override
    @Transactional(readOnly = true)
    public Resolution resolve(ResolveCommand c) {
        access.staffAny(TaxReturnsPermissions.TAX_CLASSIFY, TaxReturnsPermissions.TAX_READ,
                TaxReturnsPermissions.TAX_CLASSIFY_ADMIN);
        engine.profile(c.categoryCode());
        List<String> states = props.tax().classification().ruleStates();
        String state = c.shipToState() == null || c.shipToState().isBlank() ? states.get(0)
                : c.shipToState().trim().toUpperCase(Locale.ROOT);
        Resolution r;
        if (!states.contains(state)) {
            // The rules cover the launch states only; another state is never decided by guessing.
            r = new Resolution(ResolveStatus.BLOCKED, null, null, Map.of(), null, null, List.of(),
                    engine.snapshot().ruleVersion(), "The tax rules cover " + String.join(", ", states)
                    + " only; " + state + " is not loaded.", 0);
        } else {
            r = engine.resolve(c.categoryCode(), c.facts(), c.sellerBehaviour(), c.context(),
                    c.saleDate() == null ? engine.today() : c.saleDate());
        }
        telemetry.resolved(r.status().name());
        return r;
    }
}
