package com.afrotransact.inventory.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.afrotransact.inventory.domain.InventoryStateConflict;
import com.afrotransact.inventory.domain.Lot;
import com.afrotransact.inventory.domain.LotStatus;
import com.afrotransact.inventory.domain.MovementType;
import com.afrotransact.inventory.domain.Node;
import com.afrotransact.inventory.domain.StockPosition;
import com.afrotransact.inventory.port.LotRepository;
import com.afrotransact.inventory.port.NodeRepository;
import com.afrotransact.inventory.port.PositionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/** Each lot expires in its own short transaction so a long run never holds many position locks. */
@Service
class LotLifecycleServiceImpl implements LotLifecycleService {

    private static final Logger LOG = LoggerFactory.getLogger(LotLifecycleServiceImpl.class);
    private static final String ACTOR = "system:lot-expiry";

    private final NodeRepository nodes;
    private final LotRepository lots;
    private final PositionRepository positions;
    private final StockWriter writer;
    private final InventoryAccess access;
    private final TransactionOperations tx;
    private final Clock clock;

    LotLifecycleServiceImpl(NodeRepository nodes, LotRepository lots, PositionRepository positions,
                            StockWriter writer, InventoryAccess access, TransactionOperations tx, Clock clock) {
        this.nodes = nodes;
        this.lots = lots;
        this.positions = positions;
        this.writer = writer;
        this.access = access;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public ExpiryRun expireDueLots() {
        int expired = 0;
        int deferred = 0;
        for (Node node : activeNodes()) {
            LocalDate today = node.today(clock);
            for (UUID id : lots.findExpiredIds(node.id(), today)) {
                try {
                    if (Boolean.TRUE.equals(tx.execute(s -> expireOne(id, today)))) {
                        expired++;
                    }
                } catch (InventoryStateConflict e) {
                    deferred++;
                    LOG.warn("Lot {} is past expiry but still reserved; will retry: {}", id, e.getMessage());
                }
            }
        }
        if (expired > 0 || deferred > 0) {
            LOG.info("Lot expiry run: {} expired, {} deferred", expired, deferred);
        }
        return new ExpiryRun(expired, deferred);
    }

    @Override
    public int refreshMarkdownFlags() {
        int changed = 0;
        for (Node node : activeNodes()) {
            LocalDate today = node.today(clock);
            Integer n = tx.execute(s -> lots.refreshMarkdownFlags(node.id(), today));
            changed += n == null ? 0 : n;
        }
        LOG.info("Markdown flags refreshed: {} lots changed", changed);
        return changed;
    }

    @Override
    public ExpiryRun expireNow() {
        access.require(InventoryPermissions.ADJUST);
        return expireDueLots();
    }

    @Override
    public int markdownNow() {
        access.require(InventoryPermissions.ADJUST);
        return refreshMarkdownFlags();
    }

    private boolean expireOne(UUID id, LocalDate today) {
        Lot unlocked = lots.findById(id).orElse(null);
        if (unlocked == null) {
            return false;
        }
        StockChanges changes = writer.begin(ACTOR);
        positions.lockBySkus(List.of(unlocked.sku())).forEach(changes::track);
        Lot lot = lots.lockById(id).orElseThrow();
        boolean eligible = lot.status() == LotStatus.SELLABLE || lot.status() == LotStatus.QUARANTINE
                || lot.status() == LotStatus.DAMAGED;
        if (!eligible || !lot.expiredOn(today)) {
            return false;
        }
        changes.trackLot(lot);
        Lot expired = lot.expire();
        StockPosition position = changes.requirePosition(lot.sku(), lot.nodeId());
        StockPosition next = lot.status() == LotStatus.SELLABLE && lot.qtyOnHand() > 0
                ? position.hold(lot.qtyOnHand()) : position;
        changes.apply(MovementType.EXPIRE, next, expired, lot.qtyOnHand(), "LOT-" + lot.lotNo(),
                "Blocked from sale at expiry " + lot.expDate());
        writer.flush(changes);
        writer.publishLot(InventoryEvents.LOT_EXPIRED, expired, lot.qtyOnHand());
        return true;
    }

    private List<Node> activeNodes() {
        return nodes.findAll().stream().filter(Node::active).toList();
    }
}
