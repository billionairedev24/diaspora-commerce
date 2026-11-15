package com.afrotransact.inventory.adapter.in.module;

import java.time.LocalDate;
import java.util.List;

import com.afrotransact.inventory.api.StorageUsageApi;
import com.afrotransact.inventory.application.StorageService;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link StorageUsageApi}: the use case behind {@code GET /v1/storage/usage}, entered under the calling
 * module's principal. {@link StorageService} requires {@code inventory:storage.read} (or staff) exactly as for a token.
 */
@Component
class StorageUsageApiAdapter implements StorageUsageApi {

    private final StorageService storage;
    private final ModuleEntry entry;

    StorageUsageApiAdapter(StorageService storage, ActorResolver actors) {
        this.storage = storage;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public List<StorageDay> usage(LocalDate from, LocalDate to) {
        return entry.run(() -> storage.usage(from, to, null).stream()
                .map(d -> new StorageDay(d.ownerOrg(), d.day(), d.cubicFeet())).toList(), InventoryFailures::map);
    }
}
