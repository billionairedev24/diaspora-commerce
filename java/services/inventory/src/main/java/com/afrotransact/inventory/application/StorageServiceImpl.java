package com.afrotransact.inventory.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.afrotransact.inventory.domain.InventoryRuleViolation;
import com.afrotransact.inventory.domain.SkuDimensions;
import com.afrotransact.inventory.port.StorageRepository;
import com.afrotransact.inventory.port.StorageRepository.StorageDay;
import com.afrotransact.inventory.port.StorageRepository.StoredDimensions;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class StorageServiceImpl implements StorageService {

    private final StorageRepository storage;
    private final InventoryAccess access;
    private final ActorResolver actors;
    private final InventorySettings settings;
    private final Clock clock;

    StorageServiceImpl(StorageRepository storage, InventoryAccess access, ActorResolver actors,
                       InventorySettings settings, Clock clock) {
        this.storage = storage;
        this.access = access;
        this.actors = actors;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional
    public StoredDimensions setDimensions(String sku, BigDecimal lengthIn, BigDecimal widthIn, BigDecimal heightIn) {
        Actor actor = actors.current();
        if (!actor.can(InventoryPermissions.ADJUST) && !actor.can(InventoryPermissions.RECEIVE)) {
            throw new ForbiddenActionException("auth.missing-permission",
                    "Missing permission " + InventoryPermissions.ADJUST);
        }
        SkuDimensions d = new SkuDimensions(normalise(sku), lengthIn, widthIn, heightIn);
        storage.saveDimensions(d, actor.subject(), Instant.now(clock));
        return storage.findDimensions(d.sku()).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public StoredDimensions dimensions(String sku) {
        access.require(InventoryPermissions.READ);
        return storage.findDimensions(normalise(sku)).orElseThrow(() -> new NotFoundException(
                "inventory.dimensions-not-found", "No dimensions recorded for " + sku));
    }

    @Override
    @Transactional
    public SnapshotResult snapshotToday() {
        access.require(InventoryPermissions.ADJUST);
        return snapshotTodayAsJob();
    }

    @Override
    @Transactional
    public SnapshotResult snapshotTodayAsJob() {
        Instant now = Instant.now(clock);
        LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
        int rows = storage.snapshot(day, settings.defaultUnitCubicFeet(), now);
        return new SnapshotResult(day, rows);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StorageDay> usage(LocalDate from, LocalDate to, String ownerOrg) {
        String owner = scope(ownerOrg);
        if (from == null || to == null || to.isBefore(from)) {
            throw new InventoryRuleViolation("inventory.invalid-range", "from must be on or before to",
                    Map.of("to", "Must be on or after from"));
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new InventoryRuleViolation("inventory.invalid-range", "At most " + MAX_DAYS + " days per request",
                    Map.of("to", "At most " + MAX_DAYS + " days after from"));
        }
        return storage.usage(from, to, owner);
    }

    /** Owner filter: services with storage.read and staff may ask for anyone; sellers always get their own org. */
    private String scope(String requested) {
        Actor actor = actors.current();
        String wanted = requested == null || requested.isBlank() ? null : requested.trim();
        if (actor.type() == PrincipalType.SERVICE && actor.can(InventoryPermissions.STORAGE_READ)) {
            return wanted;
        }
        return access.ownerFilter(wanted);
    }

    private static String normalise(String sku) {
        if (sku == null || sku.isBlank() || sku.length() > 64) {
            throw new InventoryRuleViolation("inventory.invalid-sku", "Enter a SKU of at most 64 characters",
                    Map.of("sku", "Enter a SKU of at most 64 characters"));
        }
        return sku.trim().toUpperCase(Locale.ROOT);
    }
}
