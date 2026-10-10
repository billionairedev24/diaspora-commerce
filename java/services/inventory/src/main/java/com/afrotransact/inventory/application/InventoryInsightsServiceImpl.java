package com.afrotransact.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.inventory.domain.AgeBucket;
import com.afrotransact.inventory.domain.InventoryRuleViolation;
import com.afrotransact.inventory.domain.Lot;
import com.afrotransact.inventory.port.InventoryInsightsRepository;
import com.afrotransact.inventory.port.InventoryInsightsRepository.FbaStockRow;
import com.afrotransact.inventory.port.LedgerRepository;
import com.afrotransact.inventory.port.LotRepository;
import com.afrotransact.inventory.port.Page;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class InventoryInsightsServiceImpl implements InventoryInsightsService {

    static final int SUMMARY_LIMIT = 5000;
    static final int ALERT_LIMIT = 20;
    static final int TRACE_MOVEMENTS = 500;

    private final InventoryInsightsRepository insights;
    private final LotRepository lots;
    private final LedgerRepository ledger;
    private final InventoryAccess access;
    private final InventorySettings settings;

    InventoryInsightsServiceImpl(InventoryInsightsRepository insights, LotRepository lots, LedgerRepository ledger,
                                 InventoryAccess access, InventorySettings settings) {
        this.insights = insights;
        this.lots = lots;
        this.ledger = ledger;
        this.access = access;
        this.settings = settings;
    }

    @Override
    @Transactional(readOnly = true)
    public FbaInventory fbaInventory(String ownerOrg, String nodeId, String sku, boolean strandedOnly, int page,
                                     int size) {
        String owner = access.ownerFilter(blankToNull(ownerOrg));
        if (owner == null) {
            throw new InventoryRuleViolation("inventory.invalid-request", "Name the seller organisation",
                    Map.of("ownerOrg", "Required for staff"));
        }
        Page<FbaStockRow> rows = insights.fbaStock(new InventoryInsightsRepository.FbaQuery(owner, upper(nodeId),
                upper(sku), strandedOnly, Math.max(page, 0), Math.clamp(size, 1, 200)));
        List<FbaStockRow> all = insights.fbaStock(new InventoryInsightsRepository.FbaQuery(owner, null, null, false, 0,
                SUMMARY_LIMIT)).items();
        return new FbaInventory(owner, rows.map(this::item), summary(all));
    }

    @Override
    @Transactional(readOnly = true)
    public InventoryInsightsRepository.Overview overview(String nodeId, String ownerOrg) {
        String owner = access.ownerFilter(blankToNull(ownerOrg));
        return insights.overview(upper(nodeId), owner, ALERT_LIMIT);
    }

    @Override
    @Transactional(readOnly = true)
    public LotTrace lotTrace(UUID lotId) {
        Optional<String> scope = access.readScope();
        Lot lot = lots.findById(lotId).orElseThrow(() -> new NotFoundException("inventory.lot-not-found",
                "Lot " + lotId + " not found"));
        access.ensureVisible(scope, lot.ownerOrg(), "Lot " + lotId);
        String base = lot.isDamagedLot() ? lot.lotNo().substring(0, lot.lotNo().length() - Lot.DAMAGED_SUFFIX.length())
                : lot.lotNo();
        List<Lot> related = new ArrayList<>();
        for (String no : List.of(base, base + Lot.DAMAGED_SUFFIX)) {
            lots.search(new LotRepository.LotQuery(lot.sku(), null, lot.ownerOrg(), null, no, 0, 200)).items()
                    .stream().filter(l -> !l.id().equals(lot.id())).forEach(related::add);
        }
        return new LotTrace(lot, ledger.search(new LedgerRepository.MovementQuery(null, null, lotId, null, null, null,
                0, TRACE_MOVEMENTS)).items(), related, insights.consumersOfLot(lotId));
    }

    private FbaItem item(FbaStockRow r) {
        BigDecimal unit = r.unitCubicFeet() == null ? settings.defaultUnitCubicFeet() : r.unitCubicFeet();
        BigDecimal volume = unit.multiply(BigDecimal.valueOf((long) r.onHand() + r.unfulfillable()))
                .setScale(4, RoundingMode.HALF_UP);
        String reason = !r.stranded() ? null : r.openOffers() == 0 ? "NO_OFFER" : "OFFER_INACTIVE";
        return new FbaItem(r, volume, r.unitCubicFeet() != null, r.stranded(), reason);
    }

    private FbaSummary summary(List<FbaStockRow> rows) {
        long onHand = 0;
        long reserved = 0;
        long inbound = 0;
        long unfulfillable = 0;
        long quarantined = 0;
        long strandedUnits = 0;
        int strandedSkus = 0;
        BigDecimal volume = BigDecimal.ZERO;
        Map<AgeBucket, Long> ageing = new EnumMap<>(AgeBucket.class);
        for (AgeBucket b : AgeBucket.values()) {
            ageing.put(b, 0L);
        }
        for (FbaStockRow r : rows) {
            FbaItem item = item(r);
            onHand += r.onHand();
            reserved += r.reserved();
            inbound += r.inbound();
            unfulfillable += r.unfulfillable();
            quarantined += r.quarantined();
            volume = volume.add(item.cubicFeet());
            if (r.stranded()) {
                strandedUnits += r.onHand();
                strandedSkus++;
            }
            r.ageing().forEach((b, units) -> ageing.merge(b, (long) units, Long::sum));
        }
        int skus = (int) rows.stream().map(FbaStockRow::sku).distinct().count();
        return new FbaSummary(skus, onHand, reserved, onHand - reserved, inbound, unfulfillable, quarantined,
                strandedUnits, strandedSkus, volume, ageing);
    }

    private static String upper(String v) {
        return v == null || v.isBlank() ? null : v.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
