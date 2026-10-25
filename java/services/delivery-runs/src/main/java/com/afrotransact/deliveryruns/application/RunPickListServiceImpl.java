package com.afrotransact.deliveryruns.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.deliveryruns.application.RunPickLists.PickList;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.PreorderOrder;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunOrder;
import com.afrotransact.deliveryruns.domain.RunOrderStatus;
import com.afrotransact.deliveryruns.domain.RunStatus;
import com.afrotransact.deliveryruns.port.LocationRepository;
import com.afrotransact.deliveryruns.port.PreorderOrderRepository;
import com.afrotransact.deliveryruns.port.RunOrderRepository;
import com.afrotransact.deliveryruns.port.RunRepository;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class RunPickListServiceImpl implements RunPickListService {

    /** Runs a seller still has to pack for or follow. */
    private static final Set<RunStatus> ACTIVE = EnumSet.of(RunStatus.CONFIRMED, RunStatus.COLLECTING,
            RunStatus.OUT_FOR_DELIVERY);
    private static final Duration KEEP_COMPLETED = Duration.ofDays(14);
    private static final int RUN_LIMIT = 100;

    private final RunRepository runs;
    private final RunOrderRepository runOrders;
    private final PreorderOrderRepository preorders;
    private final LocationRepository locations;
    private final PickListAccess access;
    private final Clock clock;

    RunPickListServiceImpl(RunRepository runs, RunOrderRepository runOrders, PreorderOrderRepository preorders,
                           LocationRepository locations, PickListAccess access, Clock clock) {
        this.runs = runs;
        this.runOrders = runOrders;
        this.preorders = preorders;
        this.locations = locations;
        this.access = access;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PickList> forSeller(String orgId) {
        String org = access.sellerOrg(orgId);
        Instant since = Instant.now(clock).minus(KEEP_COMPLETED);
        Set<RunStatus> wanted = EnumSet.copyOf(ACTIVE);
        wanted.add(RunStatus.COMPLETED);
        return runs.find(null, wanted, RUN_LIMIT).stream()
                .filter(r -> r.status() != RunStatus.COMPLETED || recentlyCompleted(r, since))
                .flatMap(r -> lists(r).stream())
                .filter(p -> p.sellerOrgId().equals(org))
                .sorted(Comparator.comparing((PickList p) -> p.sellerPickupAt() == null ? p.windowStart()
                        : p.sellerPickupAt()).thenComparing(PickList::runId))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PickList forSeller(String orgId, UUID runId) {
        String org = access.sellerOrg(orgId);
        return lists(run(runId)).stream().filter(p -> p.sellerOrgId().equals(org)).findFirst()
                .orElseThrow(() -> new NotFoundException("delivery-runs.pick-list-not-found",
                        "This run has nothing to collect from your organisation"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PickList> forRun(UUID runId) {
        access.operator();
        return lists(run(runId));
    }

    private static boolean recentlyCompleted(Run r, Instant since) {
        Instant done = r.milestones().completedAt();
        return done != null && done.isAfter(since);
    }

    private List<PickList> lists(Run run) {
        List<RunOrder> slots = runOrders.findByRun(run.id(), RunOrderStatus.CONFIRMED);
        if (slots.isEmpty()) {
            return List.of();
        }
        Map<String, PreorderOrder> orders = new HashMap<>();
        for (PreorderOrder o : preorders.findByIds(slots.stream().map(RunOrder::orderId).toList())) {
            orders.put(o.orderId(), o);
        }
        Location location = locations.findById(run.locationId()).orElseThrow();
        return PickListBuilder.build(run, location, slots, orders);
    }

    private Run run(UUID runId) {
        return runs.findById(runId).orElseThrow(() -> new NotFoundException("delivery-runs.run-not-found",
                "Run not found"));
    }
}
