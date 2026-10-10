package com.afrotransact.seller.application;

import java.util.List;

import com.afrotransact.seller.domain.ComplianceHistoryEntry;
import com.afrotransact.seller.domain.ConnectAccount;

/** Account health (sellers) or the compliance sheet (staff): status, flags, requirements with forms, history. */
public record AccountHealthView(ConnectAccount account, String orgName, List<RequirementItem> requirements,
                                List<ComplianceHistoryEntry> history) {

    public AccountHealthView {
        requirements = List.copyOf(requirements);
        history = List.copyOf(history);
    }
}
