package com.afrotransact.inventory.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import com.afrotransact.inventory.domain.FefoAllocator;
import com.afrotransact.inventory.domain.InsufficientStockException;
import com.afrotransact.inventory.domain.InventoryRuleViolation;
import com.afrotransact.inventory.domain.Lot;
import com.afrotransact.inventory.domain.LotAllocation;
import com.afrotransact.inventory.domain.MovementType;
import com.afrotransact.inventory.domain.Node;
import com.afrotransact.inventory.domain.Reservation;
import com.afrotransact.inventory.domain.ReservationLine;
import com.afrotransact.inventory.domain.ReservationStatus;
import com.afrotransact.inventory.domain.Shortfall;
import com.afrotransact.inventory.domain.StockPosition;
import com.afrotransact.inventory.port.InventoryMetrics;
import com.afrotransact.inventory.port.LotRepository;
import com.afrotransact.inventory.port.NodeRepository;
import com.afrotransact.inventory.port.PositionRepository;
import com.afrotransact.inventory.port.ReservationRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Concurrency model (architecture §17c):
 * <ol>
 *   <li>Claim the order id: {@code INSERT … ON CONFLICT DO NOTHING} on reservation.order_id. A concurrent retry of
 *       the same order blocks on the unique index and then replays the winner's reservation.</li>
 *   <li>Lock every position of the requested SKUs with {@code SELECT … FOR UPDATE ORDER BY sku, node_id}. All write
 *       paths lock in this order, so transactions queue instead of deadlocking.</li>
 *   <li>Check availability and allocate lots FEFO for every line in memory; any shortfall throws and the whole
 *       transaction (including the claim) rolls back — all lines or none.</li>
 *   <li>Write lines, allocations, lot and position updates (version + 1), RESERVE ledger rows and outbox events.</li>
 * </ol>
 */
@Service
class ReservationServiceImpl implements ReservationService {

    private static final Logger LOG = LoggerFactory.getLogger(ReservationServiceImpl.class);
    private static final int MAX_LINES = 100;

    private final ReservationRepository reservations;
    private final PositionRepository positions;
    private final LotRepository lots;
    private final NodeRepository nodes;
    private final FefoAllocator fefo;
    private final StockWriter writer;
    private final InventoryAccess access;
    private final DomainEventPublisher events;
    private final InventoryMetrics metrics;
    private final InventorySettings settings;
    private final Clock clock;

    ReservationServiceImpl(ReservationRepository reservations, PositionRepository positions, LotRepository lots,
                           NodeRepository nodes, FefoAllocator fefo, StockWriter writer, InventoryAccess access,
                           DomainEventPublisher events, InventoryMetrics metrics, InventorySettings settings,
                           Clock clock) {
        this.reservations = reservations;
        this.positions = positions;
        this.lots = lots;
        this.nodes = nodes;
        this.fefo = fefo;
        this.writer = writer;
        this.access = access;
        this.events = events;
        this.metrics = metrics;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ReservationResult reserve(ReserveCommand raw) {
        Actor actor = access.reserver();
        ReserveCommand cmd = normalise(raw);
        Optional<Reservation> existing = reservations.findByOrderId(cmd.orderId());
        if (existing.isPresent()) {
            return replay(existing.get(), cmd);
        }
        Instant now = Instant.now(clock);
        Reservation header = Reservation.hold(UUID.randomUUID(), cmd.orderId(), cmd.idempotencyKey(), now,
                now.plus(settings.reservationTtl()));
        if (!reservations.claim(header)) {
            return replay(reservations.findByOrderId(cmd.orderId()).orElseThrow(() -> new ConflictException(
                    "inventory.reservation-in-flight", "Order " + cmd.orderId() + " is being reserved; retry")), cmd);
        }

        StockChanges changes = writer.begin(actor.subject());
        TreeSet<String> skus = cmd.lines().stream().map(ReserveCommand.Line::sku)
                .collect(Collectors.toCollection(TreeSet::new));
        positions.lockBySkus(skus).forEach(changes::track);
        Map<String, LocalDate> today = nodeDates();
        Map<String, List<UUID>> lotsByKey = new HashMap<>();
        Map<String, Integer> initialAllocatable = new LinkedHashMap<>();
        Map<String, Integer> demand = new LinkedHashMap<>();
        Map<String, Shortfall> shortfalls = new LinkedHashMap<>();
        List<ReservationLine> lines = new ArrayList<>();

        int lineNo = 0;
        for (ReserveCommand.Line line : cmd.lines()) {
            lineNo++;
            String nodeId = line.nodeId() != null ? line.nodeId()
                    : chooseNode(line.sku(), line.qty(), changes, lotsByKey, today);
            String key = line.sku() + "|" + nodeId;
            demand.merge(key, line.qty(), Integer::sum);
            Optional<StockPosition> position = nodeId == null ? Optional.empty() : changes.position(line.sku(), nodeId);
            if (position.isEmpty()) {
                shortfalls.put(key, new Shortfall(line.sku(), nodeId, demand.get(key), 0));
                continue;
            }
            List<Lot> candidates = workingLots(line.sku(), nodeId, changes, lotsByKey);
            FefoAllocator.Result result = fefo.allocate(candidates, line.qty(), dateAt(today, nodeId),
                    settings.fefoMinRemainingDays());
            int allocatable = Math.min(position.get().available(), result.allocatable());
            initialAllocatable.putIfAbsent(key, allocatable);
            if (allocatable < line.qty() || shortfalls.containsKey(key)) {
                shortfalls.put(key, new Shortfall(line.sku(), nodeId, demand.get(key), initialAllocatable.get(key)));
                continue;
            }
            StockPosition current = position.get();
            for (LotAllocation pick : result.picks()) {
                current = current.reserve(pick.qty());
                changes.apply(MovementType.RESERVE, current, changes.lot(pick.lotId()).reserve(pick.qty()),
                        pick.qty(), cmd.orderId(), "Checkout hold line " + lineNo);
            }
            lines.add(new ReservationLine(lineNo, line.sku(), nodeId, line.qty(), result.picks(), line.group(),
                    ReservationLine.LineStatus.HELD, current.ownerOrg()));
        }
        if (!shortfalls.isEmpty()) {
            metrics.reservationOutcome("insufficient");
            throw new InsufficientStockException(List.copyOf(shortfalls.values()));
        }

        Reservation reservation = header.withLines(lines);
        reservations.insertLines(reservation);
        writer.flush(changes);
        events.publish(InventoryEvents.RESERVATION_HELD, InventoryEvents.AGGREGATE_RESERVATION, cmd.orderId(),
                ReservationEventPayload.of(reservation, List.of(), null));
        metrics.reservationOutcome("held");
        return new ReservationResult(reservation, false);
    }

    @Override
    @Transactional(readOnly = true)
    public Reservation get(String orderId) {
        access.reservationReader();
        return reservations.findByOrderId(orderId).orElseThrow(() -> notFound(orderId));
    }

    @Override
    @Transactional
    public Reservation confirm(String orderId) {
        access.reserver();
        Reservation r = reservations.lockByOrderId(orderId).orElseThrow(() -> notFound(orderId));
        if (r.status() == ReservationStatus.COMMITTED || (r.isHeld() && r.expiresAt() == null)) {
            return r;
        }
        if (r.expiredAt(Instant.now(clock))) {
            return expireAndThrow(r);
        }
        return reservations.update(r.confirm(Instant.now(clock)));
    }

    @Override
    @Transactional
    public CommitResult commit(String orderId) {
        Actor actor = access.reserver();
        Reservation r = reservations.lockByOrderId(orderId).orElseThrow(() -> notFound(orderId));
        if (r.status() == ReservationStatus.COMMITTED) {
            return CommitResult.of(r, true);
        }
        if (r.expiredAt(Instant.now(clock))) {
            return expireAndThrow(r);
        }
        List<ReservationLine> shipping = r.heldLines(null);
        Reservation committed = r.commit(Instant.now(clock));
        StockChanges changes = lockFor(List.of(r), actor.subject());
        ship(shipping, changes, orderId);
        Reservation stored = reservations.update(committed);
        writer.flush(changes);
        CommitResult result = CommitResult.of(stored, false);
        events.publish(InventoryEvents.RESERVATION_COMMITTED, InventoryEvents.AGGREGATE_RESERVATION, orderId,
                ReservationEventPayload.of(stored, result.cogs(), null));
        metrics.reservationOutcome("committed");
        return result;
    }

    @Override
    @Transactional
    public CommitResult commitGroups(String orderId, Set<String> groups) {
        Actor actor = access.reserver();
        Reservation r = reservations.lockByOrderId(orderId).orElseThrow(() -> notFound(orderId));
        Set<String> wanted = requireKnownGroups(r, groups);
        List<ReservationLine> shipping = r.heldLines(wanted);
        if (!shipping.isEmpty() && r.expiredAt(Instant.now(clock))) {
            return expireAndThrow(r);
        }
        Reservation committed = r.commitGroups(wanted, Instant.now(clock));
        if (shipping.isEmpty()) {
            return CommitResult.ofLines(r, r.committedLines().stream().filter(l -> l.inAny(wanted)).toList(), true);
        }
        StockChanges changes = lockFor(List.of(r), actor.subject());
        ship(shipping, changes, orderId);
        Reservation stored = reservations.update(committed);
        writer.flush(changes);
        List<ReservationLine> shipped = stored.lines().stream().filter(l -> l.inAny(wanted)).toList();
        CommitResult result = CommitResult.ofLines(stored, shipped, false);
        events.publish(InventoryEvents.RESERVATION_GROUP_COMMITTED, InventoryEvents.AGGREGATE_RESERVATION, orderId,
                ReservationEventPayload.ofGroups(stored, shipped, wanted, result.cogs(), null));
        metrics.reservationOutcome("group_committed");
        return result;
    }

    @Override
    @Transactional
    public Reservation releaseGroups(String orderId, Set<String> groups, String reason) {
        Actor actor = access.reserver();
        Reservation r = reservations.lockByOrderId(orderId).orElseThrow(() -> notFound(orderId));
        Set<String> wanted = requireKnownGroups(r, groups);
        List<ReservationLine> giving = r.heldLines(wanted);
        Reservation released = r.releaseGroups(wanted, Instant.now(clock));
        if (giving.isEmpty()) {
            return r;
        }
        StockChanges changes = lockFor(List.of(r), actor.subject());
        String why = reason == null || reason.isBlank() ? "Shipment cancelled" : reason.trim();
        giveBack(giving, changes, orderId, why);
        Reservation stored = reservations.update(released);
        writer.flush(changes);
        events.publish(InventoryEvents.RESERVATION_GROUP_RELEASED, InventoryEvents.AGGREGATE_RESERVATION, orderId,
                ReservationEventPayload.ofGroups(stored, stored.lines().stream().filter(l -> l.inAny(wanted)).toList(),
                        wanted, List.of(), why));
        metrics.reservationOutcome("group_released");
        return stored;
    }

    private Set<String> requireKnownGroups(Reservation r, Set<String> groups) {
        Set<String> wanted = groups == null ? Set.of() : groups.stream().filter(Objects::nonNull).map(String::trim)
                .filter(g -> !g.isEmpty()).collect(Collectors.toCollection(TreeSet::new));
        if (wanted.isEmpty()) {
            throw new InventoryRuleViolation("inventory.invalid-request", "Name at least one group",
                    Map.of("groups", "Required"));
        }
        Set<String> unknown = r.unknownGroups(wanted);
        if (!unknown.isEmpty()) {
            throw new NotFoundException("inventory.group-not-found", "Order " + r.orderId()
                    + " has no reserved lines for " + String.join(", ", new TreeSet<>(unknown)));
        }
        return wanted;
    }

    private static void ship(List<ReservationLine> lines, StockChanges changes, String orderId) {
        for (ReservationLine line : lines) {
            StockPosition current = changes.requirePosition(line.sku(), line.nodeId());
            for (LotAllocation a : line.allocations()) {
                current = current.ship(a.qty());
                changes.apply(MovementType.SHIP, current, changes.lot(a.lotId()).ship(a.qty()), a.qty(), orderId,
                        "Shipped line " + line.lineNo());
            }
        }
    }

    private static void giveBack(List<ReservationLine> lines, StockChanges changes, String orderId, String reason) {
        for (ReservationLine line : lines) {
            StockPosition current = changes.requirePosition(line.sku(), line.nodeId());
            for (LotAllocation a : line.allocations()) {
                current = current.release(a.qty());
                changes.apply(MovementType.RELEASE, current, changes.lot(a.lotId()).release(a.qty()), a.qty(),
                        orderId, reason);
            }
        }
    }

    @Override
    @Transactional
    public Reservation release(String orderId, String reason) {
        Actor actor = access.reserver();
        Reservation r = reservations.lockByOrderId(orderId).orElseThrow(() -> notFound(orderId));
        if (r.status() == ReservationStatus.RELEASED || r.status() == ReservationStatus.EXPIRED) {
            return r;
        }
        Reservation released = r.release(Instant.now(clock));
        StockChanges changes = lockFor(List.of(r), actor.subject());
        String why = reason == null || reason.isBlank() ? "Released by orders" : reason.trim();
        Reservation stored = releaseAll(List.of(r), List.of(released), changes, why).getFirst();
        metrics.reservationOutcome("released");
        return stored;
    }

    @Override
    @Transactional
    public int expireDue(int limit) {
        Instant now = Instant.now(clock);
        List<Reservation> due = reservations.lockExpired(now, limit);
        if (due.isEmpty()) {
            return 0;
        }
        StockChanges changes = lockFor(due, "system:reservation-expiry");
        releaseAll(due, due.stream().map(r -> r.expire(now)).toList(), changes, "Checkout hold expired");
        due.forEach(r -> metrics.reservationOutcome("expired"));
        LOG.info("Released {} expired checkout reservations", due.size());
        return due.size();
    }

    /**
     * Releases the held lines of HELD reservations ({@code before}, now {@code ended} RELEASED / EXPIRED) whose
     * positions and lots are locked in {@code changes}.
     */
    private List<Reservation> releaseAll(List<Reservation> before, List<Reservation> ended, StockChanges changes,
                                         String reason) {
        List<Reservation> stored = new ArrayList<>();
        for (int i = 0; i < ended.size(); i++) {
            Reservation r = ended.get(i);
            giveBack(before.get(i).heldLines(null), changes, r.orderId(), reason);
            stored.add(reservations.update(r));
        }
        writer.flush(changes);
        for (Reservation r : stored) {
            events.publish(InventoryEvents.RESERVATION_RELEASED, InventoryEvents.AGGREGATE_RESERVATION, r.orderId(),
                    ReservationEventPayload.of(r, List.of(), reason));
        }
        return stored;
    }

    /** Locks positions (key order) and then the allocated lots of the given reservations. */
    private StockChanges lockFor(Collection<Reservation> rs, String actor) {
        StockChanges changes = writer.begin(actor);
        TreeSet<String> skus = rs.stream().flatMap(r -> r.lines().stream()).map(ReservationLine::sku)
                .collect(Collectors.toCollection(TreeSet::new));
        positions.lockBySkus(skus).forEach(changes::track);
        List<UUID> lotIds = rs.stream().flatMap(r -> r.lines().stream()).flatMap(l -> l.allocations().stream())
                .map(LotAllocation::lotId).distinct().toList();
        lots.lockByIds(lotIds).forEach(changes::trackLot);
        return changes;
    }

    /** A HELD reservation past its TTL that the sweeper has not released yet: refuse the late confirm / commit. */
    private static <T> T expireAndThrow(Reservation r) {
        throw new ConflictException("inventory.reservation-expired", "The checkout hold for order " + r.orderId()
                + " expired at " + r.expiresAt() + "; reserve again");
    }

    /** The node with the most allocatable units for the SKU (null when no node stocks it). */
    private String chooseNode(String sku, int qty, StockChanges changes, Map<String, List<UUID>> lotsByKey,
                              Map<String, LocalDate> today) {
        String best = null;
        int bestUnits = -1;
        for (StockPosition p : changes.positionsOf(sku)) {
            int units = Math.min(p.available(), fefo.allocate(workingLots(sku, p.nodeId(), changes, lotsByKey),
                    Math.max(qty, 1), dateAt(today, p.nodeId()), settings.fefoMinRemainingDays()).allocatable());
            if (units > bestUnits) {
                best = p.nodeId();
                bestUnits = units;
            }
        }
        return best;
    }

    /** Current (in-transaction) state of the allocatable lots of one SKU at one node, locking them on first use. */
    private List<Lot> workingLots(String sku, String nodeId, StockChanges changes, Map<String, List<UUID>> lotsByKey) {
        List<UUID> ids = lotsByKey.computeIfAbsent(sku + "|" + nodeId, k -> {
            List<Lot> locked = lots.lockAllocatable(sku, nodeId);
            locked.forEach(changes::trackLot);
            return locked.stream().map(Lot::id).toList();
        });
        return ids.stream().map(changes::lot).toList();
    }

    private Map<String, LocalDate> nodeDates() {
        return nodes.findAll().stream().collect(Collectors.toMap(Node::id, n -> n.today(clock)));
    }

    private LocalDate dateAt(Map<String, LocalDate> today, String nodeId) {
        return today.getOrDefault(nodeId, LocalDate.now(clock));
    }

    private ReservationResult replay(Reservation existing, ReserveCommand cmd) {
        if (!sameLines(existing, cmd)) {
            throw new ConflictException("inventory.idempotency-mismatch", "Order " + cmd.orderId()
                    + " was already reserved with different lines");
        }
        metrics.reservationOutcome("replayed");
        return new ReservationResult(existing, true);
    }

    private static boolean sameLines(Reservation existing, ReserveCommand cmd) {
        if (existing.lines().isEmpty()) {
            return true;
        }
        if (existing.lines().size() != cmd.lines().size()) {
            return false;
        }
        for (int i = 0; i < cmd.lines().size(); i++) {
            ReserveCommand.Line asked = cmd.lines().get(i);
            ReservationLine held = existing.lines().get(i);
            if (!held.sku().equals(asked.sku()) || held.qty() != asked.qty()
                    || (asked.nodeId() != null && !asked.nodeId().equals(held.nodeId()))
                    || !Objects.equals(asked.group(), held.group())) {
                return false;
            }
        }
        return true;
    }

    private static ReserveCommand normalise(ReserveCommand cmd) {
        if (cmd.orderId() == null || cmd.orderId().isBlank()) {
            throw new InventoryRuleViolation("inventory.invalid-request", "orderId is required",
                    Map.of("orderId", "Required"));
        }
        if (cmd.lines().isEmpty() || cmd.lines().size() > MAX_LINES) {
            throw new InventoryRuleViolation("inventory.invalid-request", "An order needs 1 to " + MAX_LINES + " lines",
                    Map.of("lines", "1 to " + MAX_LINES + " lines"));
        }
        Map<String, String> errors = new LinkedHashMap<>();
        List<ReserveCommand.Line> lines = new ArrayList<>();
        for (int i = 0; i < cmd.lines().size(); i++) {
            ReserveCommand.Line l = cmd.lines().get(i);
            if (l.sku() == null || l.sku().isBlank()) {
                errors.put("lines[" + i + "].sku", "Required");
            }
            if (l.qty() <= 0) {
                errors.put("lines[" + i + "].qty", "Must be at least 1");
            }
            if (l.group() != null && l.group().trim().length() > 80) {
                errors.put("lines[" + i + "].group", "At most 80 characters");
            }
            lines.add(new ReserveCommand.Line(Objects.requireNonNullElse(l.sku(), "").trim().toUpperCase(Locale.ROOT),
                    l.nodeId() == null || l.nodeId().isBlank() ? null : l.nodeId().trim().toUpperCase(Locale.ROOT),
                    l.qty(), l.group() == null || l.group().isBlank() ? null : l.group().trim()));
        }
        if (!errors.isEmpty()) {
            throw new InventoryRuleViolation("inventory.invalid-request", "Invalid reservation lines", errors);
        }
        return new ReserveCommand(cmd.orderId().trim(), cmd.idempotencyKey(), lines);
    }

    private static NotFoundException notFound(String orderId) {
        return new NotFoundException("inventory.reservation-not-found", "No reservation for order " + orderId);
    }
}
