package com.afrotransact.inventory.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import com.afrotransact.inventory.domain.InventoryRuleViolation;
import com.afrotransact.inventory.domain.Movement;
import com.afrotransact.inventory.domain.StockPosition;
import com.afrotransact.inventory.port.LedgerRepository;
import com.afrotransact.inventory.port.Page;
import com.afrotransact.inventory.port.PositionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class StockQueryServiceImpl implements StockQueryService {

    static final int MAX_SKUS = 100;

    private final PositionRepository positions;
    private final LedgerRepository ledger;
    private final InventoryAccess access;

    StockQueryServiceImpl(PositionRepository positions, LedgerRepository ledger, InventoryAccess access) {
        this.positions = positions;
        this.ledger = ledger;
        this.access = access;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Availability> availability(List<String> skus) {
        LinkedHashSet<String> wanted = skus == null ? new LinkedHashSet<>() : skus.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (wanted.isEmpty() || wanted.size() > MAX_SKUS) {
            throw new InventoryRuleViolation("inventory.invalid-request", "Ask for 1 to " + MAX_SKUS + " SKUs",
                    Map.of("skus", "1 to " + MAX_SKUS));
        }
        Map<String, List<StockPosition>> bySku = positions.findBySkus(wanted, null).stream()
                .collect(Collectors.groupingBy(StockPosition::sku));
        List<Availability> out = new ArrayList<>();
        for (String sku : wanted) {
            List<StockPosition> at = bySku.getOrDefault(sku, List.of());
            int available = at.stream().mapToInt(StockPosition::available).sum();
            int inbound = at.stream().mapToInt(StockPosition::inbound).sum();
            out.add(new Availability(sku, available, inbound, available > 0, at.stream()
                    .map(p -> new Availability.NodeAvailability(p.nodeId(), p.available(), p.inbound(), p.version()))
                    .toList()));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StockPosition> positions(PositionRepository.PositionQuery q) {
        String owner = access.ownerFilter(q.ownerOrg());
        return positions.search(new PositionRepository.PositionQuery(upper(q.sku()), upper(q.nodeId()), owner,
                q.lowStockOnly(), Math.max(q.page(), 0), Math.clamp(q.size(), 1, 200)));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Movement> movements(LedgerRepository.MovementQuery q) {
        String owner = access.ownerFilter(q.ownerOrg());
        return ledger.search(new LedgerRepository.MovementQuery(upper(q.sku()), upper(q.nodeId()), q.lotId(),
                q.reference(), owner, q.type(), Math.max(q.page(), 0), Math.clamp(q.size(), 1, 500)));
    }

    private static String upper(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase(Locale.ROOT);
    }
}
