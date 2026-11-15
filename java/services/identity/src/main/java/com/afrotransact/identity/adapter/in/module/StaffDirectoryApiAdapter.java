package com.afrotransact.identity.adapter.in.module;

import java.util.List;

import com.afrotransact.identity.api.StaffDirectoryApi;
import com.afrotransact.identity.application.ContactLookupService;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link StaffDirectoryApi}: the use case behind {@code GET /v1/internal/staff}, entered under the
 * calling module's principal. {@link ContactLookupService} requires a service principal with {@code users:read} exactly as it does
 * for a token.
 */
@Component
class StaffDirectoryApiAdapter implements StaffDirectoryApi {

    private final ContactLookupService lookups;
    private final ModuleEntry entry;

    StaffDirectoryApiAdapter(ContactLookupService lookups, ActorResolver actors) {
        this.lookups = lookups;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public List<StaffMember> staff(String permission, String role, List<String> ids) {
        return entry.run(() -> lookups.staff(permission, role, ids).stream()
                .map(m -> new StaffMember(m.userId(), m.name(), m.email())).toList());
    }
}
