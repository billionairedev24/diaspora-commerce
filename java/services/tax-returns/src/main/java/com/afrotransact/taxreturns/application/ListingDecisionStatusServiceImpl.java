package com.afrotransact.taxreturns.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.taxreturns.application.LineDecisionService.ListingStatus;
import com.afrotransact.taxreturns.application.LineDecisionService.Request;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ListingDecisionStatusServiceImpl implements ListingDecisionStatusService {

    private final LineDecisionService lines;
    private final DecisionEngine engine;
    private final Access access;

    ListingDecisionStatusServiceImpl(LineDecisionService lines, DecisionEngine engine, Access access) {
        this.lines = lines;
        this.engine = engine;
        this.access = access;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, ListingStatus> statusOf(List<Item> items) {
        access.staffAny(TaxReturnsPermissions.TAX_CLASSIFY, TaxReturnsPermissions.TAX_READ,
                TaxReturnsPermissions.TAX_CLASSIFY_ADMIN);
        if (items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("At most " + MAX_ITEMS + " variants per call");
        }
        Map<String, Request> asks = new LinkedHashMap<>();
        for (Item i : items) {
            asks.putIfAbsent(i.atin(), new Request(i.atin(), i.atin(), i.sellerOrgId(), Map.of()));
        }
        return lines.status(List.copyOf(asks.values()), engine.today());
    }
}
