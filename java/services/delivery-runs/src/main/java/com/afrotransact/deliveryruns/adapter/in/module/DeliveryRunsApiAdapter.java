package com.afrotransact.deliveryruns.adapter.in.module;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.afrotransact.deliveryruns.api.DeliveryRunsApi;
import com.afrotransact.deliveryruns.application.AvailableRun;
import com.afrotransact.deliveryruns.application.DeliveryFeeService;
import com.afrotransact.deliveryruns.application.DispatchList;
import com.afrotransact.deliveryruns.application.PreorderPublicService;
import com.afrotransact.deliveryruns.application.PublicRunService;
import com.afrotransact.deliveryruns.application.ReserveCommand;
import com.afrotransact.deliveryruns.application.RunSlotService;
import com.afrotransact.deliveryruns.domain.DeliveryKind;
import com.afrotransact.deliveryruns.domain.FeeSnapshot;
import com.afrotransact.deliveryruns.domain.GeoPoint;
import com.afrotransact.deliveryruns.domain.PaymentMethod;
import com.afrotransact.deliveryruns.domain.PreorderResolver;
import com.afrotransact.deliveryruns.domain.DropMode;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.domain.Resolution;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunOrder;
import com.afrotransact.deliveryruns.domain.RunOrderMoney;
import com.afrotransact.deliveryruns.domain.RunOrderStatus;
import com.afrotransact.deliveryruns.domain.RunTerms;
import com.afrotransact.deliveryruns.domain.WindowDetails;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link DeliveryRunsApi}: the use cases behind the public run read and the service endpoints orders and
 * fulfilment call, entered under the calling module's principal. The application services check the scopes
 * ({@code delivery-runs:reserve}, {@code delivery-runs:read}) exactly as for a token; the answers carry what the endpoints' responses
 * carry (the fields the callers read), built from the same domain objects. A reservation asks for exactly the run named
 * ({@code allowNextRun=false}, one package), as the HTTP client does.
 */
@Component
class DeliveryRunsApiAdapter implements DeliveryRunsApi {

    private static final int MAX_EARLY_HOURS = 720;

    private final PublicRunService publicRuns;
    private final RunSlotService slots;
    private final PreorderPublicService preorders;
    private final DeliveryFeeService fees;
    private final ModuleEntry entry;

    DeliveryRunsApiAdapter(PublicRunService publicRuns, RunSlotService slots, PreorderPublicService preorders,
                           DeliveryFeeService fees, ActorResolver actors) {
        this.publicRuns = publicRuns;
        this.slots = slots;
        this.preorders = preorders;
        this.fees = fees;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public List<OpenRun> openRuns(String zip, int earlyAccessHours) {
        return entry.run(() -> publicRuns.available(zip, null, null, clamp(earlyAccessHours)).stream()
                .map(DeliveryRunsApiAdapter::open).toList(), DeliveryRunsFailures::map);
    }

    @Override
    public void reserve(String runId, String orderId, String customerId, int earlyAccessHours, RunCharge charge) {
        entry.runVoid(() -> {
            RunOrderMoney money = charge == null ? null : RunOrderMoney.charged(charge.feeMinor(), charge.totalMinor(),
                    charge.kind() == null ? null : DeliveryKind.valueOf(charge.kind()));
            slots.reserve(new ReserveCommand(uuid(runId), orderId, customerId, 1, false,
                    earlyAccessHours > 0 ? earlyAccessHours : null, money));
        }, DeliveryRunsFailures::map);
    }

    @Override
    public void release(String runId, String orderId) {
        entry.runVoid(() -> slots.release(uuid(runId), orderId), DeliveryRunsFailures::map);
    }

    @Override
    public List<Eligibility> eligibility(String country, String region, String city, List<String> atins, int earlyAccessHours) {
        return entry.run(() -> {
            if (country == null || country.isBlank() || region == null || region.isBlank() || atins == null || atins.isEmpty()) {
                throw new ModuleApiException(400, "delivery-runs.invalid-input", "country, region and atins are required");
            }
            PreorderPublicService.EligibilityResult r = preorders.eligibility(country, region, city, atins,
                    clamp(earlyAccessHours));
            return r.items().stream().map(e -> eligibility(e, r.resolution())).toList();
        }, DeliveryRunsFailures::map);
    }

    @Override
    public Optional<FeeRule> carrierFee(String currency, Long carrierQuoteMinor, long itemsMinor) {
        return entry.run(() -> fees.carrier(currency, carrierQuoteMinor, itemsMinor).map(DeliveryRunsApiAdapter::rule),
                DeliveryRunsFailures::map);
    }

    @Override
    public RunPlan dispatch(String runId) {
        return entry.run(() -> plan(slots.dispatch(uuid(runId))), DeliveryRunsFailures::map);
    }

    private static int clamp(int hours) {
        return Math.max(0, Math.min(hours, MAX_EARLY_HOURS));
    }

    /** A path or body id that is not a UUID is the 400 the HTTP binding answers. */
    private static UUID uuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            throw new ModuleApiException(400, "delivery-runs.invalid-input", "Invalid value for runId");
        }
    }

    private static OpenRun open(AvailableRun a) {
        Run r = a.run();
        RunTerms t = r.terms();
        return new OpenRun(r.id().toString(), a.location().details().name(), t.cutoffAt(),
                t.window().start().atZone(a.location().details().timeZone()).toLocalDate(),
                a.location().details().timeZone().getId(), t.feeMinor(), t.currency(), t.dropMode().name(), rule(a.feeRule()),
                t.minOrders(), r.reservedOrders(), r.isFull(1));
    }

    private static Eligibility eligibility(PreorderResolver.Eligibility e, Resolution r) {
        PreorderWindow w = e.window();
        if (w == null) {
            return new Eligibility(e.atin(), false, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null);
        }
        WindowDetails d = w.details();
        Set<String> methods = r.settings().payment().allowedMethods().stream().map(PaymentMethod::name).sorted()
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new Eligibility(e.atin(), e.preorder(), w.id().toString(), w.code(), d.name(),
                e.stage() == null ? null : e.stage().name(), d.deliversOn(), d.closesAt(), r.doorFee() != null, d.pickup(),
                r.doorFee() == null ? null : r.doorFee().amountMinor(), r.pickupFee() == null ? null : r.pickupFee().amountMinor(),
                d.currency(), methods, d.pickupPointId(), rule(r.doorFee()), rule(r.pickupFee()));
    }

    private static FeeRule rule(FeeSnapshot s) {
        return s == null ? null : new FeeRule(s.method().name(), s.ruleId() == null ? null : s.ruleId().toString(), s.ruleVersion(),
                s.currency(), s.amountMinor(), s.source().name(), s.band(), s.distanceMiles());
    }

    private static RunPlan plan(DispatchList d) {
        RunTerms t = d.run().terms();
        var p = t.pickupPoint();
        GeoPoint origin = d.origin();
        List<String> confirmed = d.orders().stream().filter(o -> o.status() == RunOrderStatus.CONFIRMED)
                .map(RunOrder::orderId).filter(id -> id != null && !id.isEmpty()).toList();
        return new RunPlan(d.run().id().toString(), d.run().status().name(), d.location().details().name(),
                d.location().details().state(), d.location().details().timeZone().getId(), t.window().start(), t.window().end(),
                t.sellerPickupAt(), t.dropMode() != DropMode.PICKUP_POINT,
                p == null ? null : new PickupPoint(p.name(), p.line1(), p.city(), p.state(), p.postalCode(), p.hours(), p.notes()),
                origin == null ? null : origin.lat(), origin == null ? null : origin.lng(), confirmed);
    }
}
