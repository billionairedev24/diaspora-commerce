package com.afrotransact.inventory.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.inventory.domain.CycleCount;
import com.afrotransact.inventory.domain.CycleCountLine;
import com.afrotransact.inventory.domain.CycleCountStatus;
import com.afrotransact.inventory.domain.InventoryRuleViolation;
import com.afrotransact.inventory.domain.Lot;
import com.afrotransact.inventory.domain.Node;
import com.afrotransact.inventory.port.CycleCountRepository;
import com.afrotransact.inventory.port.LotRepository;
import com.afrotransact.inventory.port.NodeRepository;
import com.afrotransact.inventory.port.Page;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CycleCountServiceImpl implements CycleCountService {

    static final int MAX_LINES = 500;

    private final CycleCountRepository counts;
    private final LotRepository lots;
    private final NodeRepository nodes;
    private final LotService lotService;
    private final InventoryAccess access;
    private final Clock clock;

    CycleCountServiceImpl(CycleCountRepository counts, LotRepository lots, NodeRepository nodes, LotService lotService,
                          InventoryAccess access, Clock clock) {
        this.counts = counts;
        this.lots = lots;
        this.nodes = nodes;
        this.lotService = lotService;
        this.access = access;
        this.clock = clock;
    }

    @Override
    @Transactional
    public CycleCount start(StartCount c) {
        Actor actor = access.require(InventoryPermissions.ADJUST);
        String nodeId = upper(c.nodeId());
        Node node = nodeId == null ? null : nodes.findById(nodeId).filter(Node::active).orElse(null);
        if (node == null) {
            throw new InventoryRuleViolation("inventory.invalid-request", "Pick an active fulfilment centre",
                    Map.of("nodeId", "Unknown node"));
        }
        String sku = upper(c.sku());
        String bin = c.binPrefix() == null || c.binPrefix().isBlank() ? null : c.binPrefix().trim()
                .toUpperCase(Locale.ROOT);
        String owner = c.ownerOrg() == null || c.ownerOrg().isBlank() ? null : c.ownerOrg().trim();
        List<Lot> found = lots.findForCount(node.id(), sku, bin, owner, MAX_LINES + 1);
        if (found.isEmpty()) {
            throw new InventoryRuleViolation("inventory.cycle-count-empty", "No stock to count at " + node.id()
                    + (sku == null ? "" : " for " + sku) + (bin == null ? "" : " in bins " + bin + "…"),
                    Map.of(sku == null ? "nodeId" : "sku", "Nothing on hand"));
        }
        if (found.size() > MAX_LINES) {
            throw new InventoryRuleViolation("inventory.cycle-count-too-large", "More than " + MAX_LINES
                    + " lots: narrow the count to a SKU or a bin prefix", Map.of("binPrefix", "Too many lots"));
        }
        List<CycleCountLine> lines = new ArrayList<>();
        for (Lot l : found) {
            lines.add(new CycleCountLine(lines.size() + 1, l.id(), l.sku(), l.lotNo(), l.bin(), l.expDate(),
                    l.qtyOnHand(), null, null, null, null, null, null));
        }
        Instant now = Instant.now(clock);
        String note = c.note() == null || c.note().isBlank() ? null : c.note().trim();
        CycleCount count = new CycleCount(UUID.randomUUID(), counts.nextNumber(), node.id(), sku, bin, owner,
                c.blind(), CycleCountStatus.OPEN, note, lines, actor.subject(), now, null, null, null, now, 0);
        counts.insert(count);
        return counts.findById(count.id()).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public CycleCount get(UUID id) {
        Optional<String> scope = access.readScope();
        CycleCount c = counts.findById(id).orElseThrow(() -> notFound(id));
        if (scope.isPresent() && !scope.get().equals(c.ownerOrg())) {
            throw notFound(id);
        }
        return c;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CycleCount> list(CycleCountRepository.CountQuery q) {
        String owner = access.ownerFilter(q.ownerOrg());
        return counts.search(new CycleCountRepository.CountQuery(upper(q.nodeId()), q.status(), upper(q.sku()), owner,
                Math.max(q.page(), 0), Math.clamp(q.size(), 1, 200)));
    }

    @Override
    @Transactional
    public CycleCount record(UUID id, List<CountedLine> lines, Long expectedVersion) {
        Actor actor = access.require(InventoryPermissions.ADJUST);
        if (lines == null || lines.isEmpty()) {
            throw new InventoryRuleViolation("inventory.invalid-request", "Count at least one line",
                    Map.of("lines", "Required"));
        }
        CycleCount current = lock(id, expectedVersion);
        Map<Integer, CycleCount.Entry> entries = new LinkedHashMap<>();
        for (CountedLine l : lines) {
            String note = l.note() == null || l.note().isBlank() ? null : l.note().trim();
            entries.put(l.lineNo(), new CycleCount.Entry(l.countedQty(), l.reasonCode(), note));
        }
        return counts.update(current.count(entries, actor.subject(), Instant.now(clock)));
    }

    @Override
    @Transactional
    public CycleCount post(UUID id, Long expectedVersion) {
        Actor actor = access.require(InventoryPermissions.ADJUST);
        CycleCount current = lock(id, expectedVersion);
        current.requirePostable();
        List<CycleCountLine> posted = new ArrayList<>();
        for (CycleCountLine l : current.lines()) {
            int variance = l.variance();
            if (variance == 0) {
                posted.add(l.posted(0));
                continue;
            }
            // Counted − expected on top of what is on hand now: picks and receipts during the count are kept.
            Lot lot = lots.findById(l.lotId()).orElseThrow(() -> notFound(l.lotId()));
            int target = lot.qtyOnHand() + variance;
            if (target < lot.qtyReserved()) {
                throw new InventoryRuleViolation("inventory.cycle-count-below-reserved", "Lot " + l.lotNo() + " has "
                        + lot.qtyReserved() + " units reserved for orders; the count would leave " + target,
                        Map.of("lines[" + l.lineNo() + "].countedQty", "Below what is reserved"));
            }
            String reason = current.number() + " · " + l.reasonCode().name().replace('_', ' ').toLowerCase(Locale.ROOT)
                    + (l.note() == null ? "" : " · " + l.note());
            lotService.adjust(l.lotId(), target, reason, "cycle-count-" + current.id() + "-" + l.lineNo());
            posted.add(l.posted(variance));
        }
        return counts.update(current.posted(posted, actor.subject(), Instant.now(clock)));
    }

    @Override
    @Transactional
    public CycleCount cancel(UUID id, Long expectedVersion) {
        access.require(InventoryPermissions.ADJUST);
        return counts.update(lock(id, expectedVersion).cancelled(Instant.now(clock)));
    }

    private CycleCount lock(UUID id, Long expectedVersion) {
        CycleCount c = counts.lockById(id).orElseThrow(() -> notFound(id));
        if (expectedVersion != null && expectedVersion != c.version()) {
            throw new ConflictException("inventory.version-mismatch", "Cycle count " + c.number()
                    + " changed meanwhile (version " + c.version() + "); reload and retry");
        }
        return c;
    }

    private static NotFoundException notFound(UUID id) {
        return new NotFoundException("inventory.not-found", "No cycle count or lot " + id);
    }

    private static String upper(String v) {
        return v == null || v.isBlank() ? null : v.trim().toUpperCase(Locale.ROOT);
    }
}
