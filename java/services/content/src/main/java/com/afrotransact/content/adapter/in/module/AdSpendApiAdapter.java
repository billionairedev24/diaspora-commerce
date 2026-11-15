package com.afrotransact.content.adapter.in.module;

import java.time.LocalDate;
import java.util.List;

import com.afrotransact.content.api.AdSpendApi;
import com.afrotransact.content.application.AdSpendService;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link AdSpendApi}: the use case behind {@code GET /v1/ads/spend}, entered under the calling module's
 * principal. {@link AdSpendService} requires {@code ads:spend.read} (or staff) exactly as for a token.
 */
@Component
class AdSpendApiAdapter implements AdSpendApi {

    private final AdSpendService spend;
    private final ModuleEntry entry;

    AdSpendApiAdapter(AdSpendService spend, ActorResolver actors) {
        this.spend = spend;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public List<OrgDaySpend> spend(LocalDate from, LocalDate to) {
        return entry.run(() -> spend.spend(from, to).stream()
                .map(s -> new OrgDaySpend(s.sellerOrgId(), s.day(), s.currency(), s.spendMinor())).toList());
    }
}
