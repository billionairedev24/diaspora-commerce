package com.afrotransact.deliveryruns.adapter.in.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.deliveryruns.application.LateOrderCommand;
import com.afrotransact.deliveryruns.application.PreorderOrderService.LateOrderResult;
import com.afrotransact.deliveryruns.application.PreorderOrderService.OrderPage;
import com.afrotransact.deliveryruns.application.PreorderPublicService.EligibilityResult;
import com.afrotransact.deliveryruns.application.PreorderPublicService.OpenWindow;
import com.afrotransact.deliveryruns.application.SettingsCommand;
import com.afrotransact.deliveryruns.application.WindowCommand;
import com.afrotransact.deliveryruns.application.WindowPage;
import com.afrotransact.deliveryruns.application.WindowView;
import com.afrotransact.deliveryruns.domain.AreaKey;
import com.afrotransact.deliveryruns.domain.AttributionChannel;
import com.afrotransact.deliveryruns.domain.CancelUntil;
import com.afrotransact.deliveryruns.domain.CommKind;
import com.afrotransact.deliveryruns.domain.CreativeRef;
import com.afrotransact.deliveryruns.domain.CreativeSlot;
import com.afrotransact.deliveryruns.domain.Fallback;
import com.afrotransact.deliveryruns.domain.Gsm7;
import com.afrotransact.deliveryruns.domain.LateAddReason;
import com.afrotransact.deliveryruns.domain.LateAddSettings;
import com.afrotransact.deliveryruns.domain.LatePaymentMethod;
import com.afrotransact.deliveryruns.domain.MissedDeliverySettings;
import com.afrotransact.deliveryruns.domain.Money;
import com.afrotransact.deliveryruns.domain.PaymentMethod;
import com.afrotransact.deliveryruns.domain.PaymentSettings;
import com.afrotransact.deliveryruns.domain.Placement;
import com.afrotransact.deliveryruns.domain.PreorderFulfilment;
import com.afrotransact.deliveryruns.domain.PreorderOrder;
import com.afrotransact.deliveryruns.domain.PreorderOrderStatus;
import com.afrotransact.deliveryruns.domain.PreorderPackage;
import com.afrotransact.deliveryruns.domain.PreorderResolver.Eligibility;
import com.afrotransact.deliveryruns.domain.PreorderSettings;
import com.afrotransact.deliveryruns.domain.PreorderStatus;
import com.afrotransact.deliveryruns.domain.PreorderSummary;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.domain.Resolution;
import com.afrotransact.deliveryruns.domain.RuleViolation;
import com.afrotransact.deliveryruns.domain.WindowDetails;
import com.afrotransact.deliveryruns.port.OrdersGateway.Address;
import com.afrotransact.deliveryruns.port.OrdersGateway.Item;
import com.afrotransact.deliveryruns.port.OrdersGateway.NewAddress;
import com.afrotransact.deliveryruns.port.ProductSearch;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Request and response bodies of the pre-order endpoints. The staff shapes are the ones the back office reads through
 * the staff BFF ({@code web/packages/api-client/src/staff-preorders.ts}): money in minor units, {@code deliversOn} a
 * calendar date, every other date an ISO-8601 instant. Fields beyond the console's are additions.
 */
final class PreorderDtos {

    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("MMM d", Locale.US);

    private PreorderDtos() {
    }

    // ---- staff: windows -------------------------------------------------------------------------------------------

    @Schema(name = "PreorderWindowRequest", description = """
            Create (draft) or edit a pre-order window. Drafts may be incomplete; a published window must keep passing
            its publish checks. Fees are minor units of the window's currency (the first target's country); null fee
            overrides use the platform payment settings.""")
    record WindowRequest(@Size(max = 120) @Schema(example = "Lubbock Pre-order #7") String name,
                         @Size(max = 50) @Schema(description = "country/region or country/region/city",
                                 example = "[\"US/TX/Lubbock\"]") List<String> targets,
                         @NotNull Instant opensAt, @NotNull Instant closesAt,
                         @NotNull @Schema(example = "2026-10-25") LocalDate deliversOn,
                         @Size(max = 80) String ribbon, @Size(max = 140) String headline,
                         @Size(max = 320) @Schema(description = "One SMS: 160 GSM-7 characters") String smsCopy,
                         Set<String> categories, Boolean door, Boolean pickup,
                         @Size(max = 200) @Schema(description = "Pickup point shown to shoppers") String pickupPoint,
                         @PositiveOrZero @Max(10_000) Long doorFeeOverride,
                         @PositiveOrZero @Max(10_000) Long pickupFeeOverride, Boolean showCount,
                         @PositiveOrZero Long socialBudget, Set<CommKind> comms,
                         @Schema(description = "IANA zone (default America/Chicago)") String timeZone,
                         @Size(max = 80) String pickupPointId,
                         @Schema(description = "Location of the closing run (null = matched by city)") UUID locationId) {

        WindowCommand toCommand() {
            return new WindowCommand(name, targets, opensAt, closesAt, deliversOn, timeZone, ribbon, headline,
                    smsCopy, categories, door, pickup, pickupPointId, pickupPoint, showCount, socialBudget, comms,
                    doorFeeOverride, pickupFeeOverride, locationId);
        }
    }

    @Schema(name = "PreorderCreativeRequest")
    record CreativeRequest(@NotNull @Schema(description = "media-service asset (READY image)") UUID assetId) {
    }

    record TargetResponse(String key, String label) {

        static TargetResponse of(AreaKey k) {
            return new TargetResponse(k.key(), k.label());
        }
    }

    record CheckResponse(String code, String message) {
    }

    @Schema(name = "PreorderCreative", description = "An uploaded creative")
    record CreativeResponse(CreativeSlot slot, UUID assetId, String url, int width, int height,
                            @Schema(description = "More than 12 % off the slot's aspect ratio (warning)")
                            boolean ratioOff) {

        static CreativeResponse of(CreativeSlot slot, CreativeRef c) {
            return new CreativeResponse(slot, c.assetId(), c.url() == null ? "" : c.url(), c.width(), c.height(),
                    c.ratioOff(slot));
        }
    }

    @Schema(name = "PreorderWindow", description = "A pre-order window; the ETag carries its version for If-Match")
    record WindowResponse(UUID id, String code, String name, List<TargetResponse> targets, String where,
                          Instant opensAt, Instant closesAt, LocalDate deliversOn, String ribbon, String headline,
                          String smsCopy, List<String> categories, boolean door, boolean pickup, String pickupPoint,
                          Long doorFeeOverride, Long pickupFeeOverride, String currency,
                          @Schema(description = "Door fee shoppers pay (override, else platform; 0 when none is set)")
                          long effectiveDoorFee,
                          @Schema(description = "Pickup fee shoppers pay") long effectivePickupFee, boolean showCount,
                          long socialBudget, List<CommKind> comms, boolean draft, boolean active,
                          PreorderStatus status, boolean areaOff, List<CreativeResponse> creatives, int orderCount,
                          long revenue, long version,
                          @Schema(description = "Open, Scheduled, Closed · delivering, Delivered, Draft, Paused or "
                                  + "Area off") String statusLabel, String timeZone,
                          @Schema(description = "GSM-7 septets (-1 = needs UCS-2)") int smsSeptets,
                          String pickupPointId, String pickupWindow, UUID locationId, UUID runId,
                          @Schema(description = "Failed publish checks") List<CheckResponse> checks,
                          boolean publishable,
                          @Schema(description = "Open windows: share of the ordering time elapsed (0–100)")
                          Integer timeProgressPct, Instant createdAt, Instant updatedAt) {

        static WindowResponse of(WindowView v) {
            PreorderWindow w = v.window();
            WindowDetails d = w.details();
            List<CreativeResponse> creatives = new ArrayList<>();
            for (CreativeSlot slot : CreativeSlot.values()) {
                CreativeRef c = w.creatives().get(slot);
                if (c != null) {
                    creatives.add(CreativeResponse.of(slot, c));
                }
            }
            Integer progress = null;
            if (v.status() == PreorderStatus.OPEN) {
                long total = d.closesAt().toEpochMilli() - d.opensAt().toEpochMilli();
                long gone = Instant.now().toEpochMilli() - d.opensAt().toEpochMilli();
                progress = total <= 0 ? 100 : (int) Math.max(0, Math.min(100, gone * 100 / total));
            }
            return new WindowResponse(w.id(), w.code(), d.name(), d.targets().stream().map(TargetResponse::of).toList(),
                    whereOf(d.targets()), d.opensAt(), d.closesAt(), d.deliversOn(), d.ribbon(), d.headline(),
                    d.smsCopy(), d.categories().stream().sorted().toList(), d.door(), d.pickup(), d.pickupAddress(),
                    minor(d.doorFeeOverride()), minor(d.pickupFeeOverride()), d.currency(), orZero(v.doorFee()),
                    orZero(v.pickupFee()), d.showCount(), d.socialBudget().minor(),
                    d.comms().stream().sorted().toList(), w.draft(), w.active(), v.status(), v.areaOff(), creatives,
                    v.count(), v.revenueMinor(), w.version(), statusLabelOf(v.status(), v.areaOff()),
                    d.timeZone().getId(), Gsm7.septets(d.smsCopy()), d.pickupPointId(), v.pickupWindow(),
                    d.locationId(), w.runId(),
                    v.checks().stream().map(c -> new CheckResponse(c.code(), c.getMessage())).toList(),
                    v.checks().isEmpty(), progress, w.createdAt(), w.updatedAt());
        }
    }

    @Schema(name = "PreorderWindowRow", description = "A row of the staff list")
    record WindowRowResponse(UUID id, String code, String name, String where, Instant opensAt, Instant closesAt,
                             LocalDate deliversOn, PreorderStatus status, boolean areaOff, int orderCount, long revenue,
                             String currency, String statusLabel, long version) {

        static WindowRowResponse of(WindowView v) {
            WindowDetails d = v.window().details();
            return new WindowRowResponse(v.window().id(), v.window().code(), d.name(), whereOf(d.targets()),
                    d.opensAt(), d.closesAt(), d.deliversOn(), v.status(), v.areaOff(), v.count(), v.revenueMinor(),
                    d.currency(), statusLabelOf(v.status(), v.areaOff()), v.window().version());
        }
    }

    @Schema(name = "PreorderWindowPage")
    record WindowPageResponse(List<WindowRowResponse> items,
                              @Schema(description = "Counts per tab: all, open, upcoming, closed, draft")
                              Map<String, Integer> counts, int total, int page, int size,
                              @Schema(description = "The pre-order master switch") boolean enabled) {

        static WindowPageResponse of(WindowPage p) {
            return new WindowPageResponse(p.items().stream().map(WindowRowResponse::of).toList(), p.counts(),
                    p.total(), p.page(), p.size(), p.enabled());
        }
    }

    // ---- staff: orders ------------------------------------------------------------------------------------------

    @Schema(name = "PreorderOrderLine")
    record OrderLineResponse(String atin, String name, String sellerName, int qty) {
    }

    @Schema(name = "PreorderOrder", description = """
            A projected pre-order (minimum PII for dispatch). paymentLinkUrl / ordersStatus are set on the response of
            a late add only.""")
    record OrderResponse(String orderId, String number, UUID windowId, String customerName, String phone,
                         String email, String addressLine, String city, String region, String zip,
                         String deliveryNote, Instant placedAt, AttributionChannel channel, String trackingTag,
                         List<OrderLineResponse> lines, int units, PreorderFulfilment fulfilment,
                         @Schema(description = "Pre-order total incl. delivery, minor units") long total,
                         PreorderOrderStatus status, String note, String customerId, String channelLabel,
                         String statusLabel, long deliveryFee, String currency, String paymentLinkUrl,
                         String ordersStatus) {

        static OrderResponse of(PreorderOrder o) {
            return of(o, null, null);
        }

        static OrderResponse of(PreorderOrder o, String paymentLinkUrl, String ordersStatus) {
            List<OrderLineResponse> lines = new ArrayList<>();
            List<PreorderPackage> active = o.packages().stream().filter(p -> !p.cancelled()).toList();
            for (PreorderPackage p : active.isEmpty() ? o.packages() : active) {
                p.lines().forEach(l -> lines.add(new OrderLineResponse(nz(l.atin()), l.name(), nz(l.seller()),
                        l.qty())));
            }
            return new OrderResponse(o.orderId(), o.number(), o.windowId(), nz(o.customerName()), nz(o.phone()),
                    nz(o.email()), nz(o.address().line()), nz(o.address().city()), nz(o.address().region()),
                    nz(o.address().zip()), nz(o.deliveryNote()), o.placedAt(), o.channel(), nz(o.trackingTag()),
                    lines, o.units(), o.fulfilment(), o.totalMinor(), o.status(), nz(o.note()), o.customerId(),
                    o.channel().label(), o.status().label(), o.deliveryFeeMinor(), o.currency(), paymentLinkUrl,
                    ordersStatus);
        }

        static OrderResponse of(LateOrderResult r) {
            return of(r.order(), r.paymentLinkUrl(), r.ordersStatus());
        }
    }

    @Schema(name = "PreorderOrderPage")
    record OrderPageResponse(List<OrderResponse> items, int total, int page, int size) {

        static OrderPageResponse of(OrderPage p) {
            return new OrderPageResponse(p.items().stream().map(OrderResponse::of).toList(), p.total(), p.page(),
                    p.size());
        }
    }

    @Schema(name = "PreorderLateOrderRequest", description = """
            A late order placed by customer service through orders-service (on-behalf order, channel AGENT_LATE).
            Send either addressId or newAddress; phoneConfirmed must be true. address carries the delivery address
            snapshot orders-service needs (recipient, street, city, state, postal code): required with a saved
            addressId; for a new address, city / state default to the window's first city.""")
    record LateOrderRequest(@NotBlank String customerId, String addressId, @Valid NewAddressBody newAddress,
                            @Valid AddressBody address, @NotNull Boolean phoneConfirmed, @NotEmpty @Size(max = 50) List<@Valid ItemBody> items,
                            @NotNull LatePaymentMethod payment, @NotNull LateAddReason reason,
                            @Size(max = 500) String note) {

        LateOrderCommand toCommand(String idempotencyKey) {
            return new LateOrderCommand(idempotencyKey, customerId, addressId,
                    newAddress == null ? null : new NewAddress(newAddress.line1(), newAddress.zip(),
                            newAddress.placeId()),
                    address == null ? null : new Address(addressId, address.fullName(), address.phone(),
                            address.line1(), address.line2(), address.city(), address.region(), address.zip(),
                            address.country(), address.instructions()), phoneConfirmed,
                    items.stream().map(i -> new Item(i.atin(), i.qty())).toList(), payment, reason, note);
        }
    }

    @Schema(name = "PreorderNewAddress")
    record NewAddressBody(@NotBlank @Size(max = 200) String line1, @NotBlank @Size(max = 20) String zip,
                          @NotBlank @Schema(description = "Google Places id it was verified with") String placeId) {
    }

    @Schema(name = "PreorderLateAddress", description = "Delivery address snapshot of a late order")
    record AddressBody(@Size(max = 120) String fullName, @Size(max = 40) String phone, @Size(max = 200) String line1,
                       @Size(max = 200) String line2, @Size(max = 120) String city,
                       @Size(max = 40) @Schema(description = "State / region code") String region,
                       @Size(max = 20) String zip, @Size(min = 2, max = 2) String country,
                       @Size(max = 240) String instructions) {
    }

    @Schema(name = "PreorderItem")
    record ItemBody(@NotBlank String atin, @Min(1) @Max(99) int qty) {
    }

    @Schema(name = "PreorderProduct", description = "A product of the window's categories (late-add picker)")
    record ProductResponse(String atin, String name, @Schema(description = "Minor units") long price,
                           String sellerName, String currency) {
    }

    @Schema(name = "PreorderProductList")
    record ProductListResponse(List<ProductResponse> items) {

        static ProductListResponse of(List<ProductSearch.Product> products) {
            return new ProductListResponse(products.stream().map(p -> new ProductResponse(p.atin(), p.name(),
                    p.priceMinor(), p.sellerName(), p.currency())).toList());
        }
    }

    // ---- staff: summary -----------------------------------------------------------------------------------------

    record KpisResponse(int preorders, int cancelled, int customers, int units,
                        @Schema(description = "Incl. delivery, minor units") long revenue, Long avgOrder, int lateAdds,
                        long deliveryRevenue, String currency) {
    }

    record DayResponse(LocalDate date, int count, boolean afterClose) {
    }

    record ChannelResponse(AttributionChannel channel, int count, String label) {
    }

    record ProductUnitsResponse(String name, int units, String atin, String seller) {
    }

    record SplitResponse(int door, int pickup, Integer doorPct, Integer pickupPct) {
    }

    record PlacementResponse(String key, String name, boolean creative,
                             @Schema(description = "0 until placement tracking reports impressions") long impressions,
                             long clicks, Double ctr, int orders) {
    }

    @Schema(name = "PreorderSummary", description = "Overview + Analytics of a window")
    record SummaryResponse(KpisResponse kpis, @Schema(description = "Orders incl. cancelled") int ordersTotal,
                           List<DayResponse> byDay, List<ChannelResponse> byChannel,
                           List<ProductUnitsResponse> topProducts, SplitResponse fulfilmentSplit,
                           List<PlacementResponse> placements) {

        static SummaryResponse of(PreorderSummary s) {
            PreorderSummary.Kpis k = s.kpis();
            PreorderSummary.FulfilmentSplit f = s.fulfilmentSplit();
            return new SummaryResponse(new KpisResponse(k.preorders(), k.cancelled(), k.customers(), k.units(),
                    k.revenueMinor(), k.avgOrderMinor(), k.lateAdds(), k.deliveryMinor(), k.currency()),
                    k.preorders() + k.cancelled(),
                    s.byDay().stream().map(d -> new DayResponse(d.date(), d.orders(), d.afterClose())).toList(),
                    s.byChannel().stream().map(c -> new ChannelResponse(c.channel(), c.orders(), c.label())).toList(),
                    s.topProducts().stream().map(p -> new ProductUnitsResponse(p.name(), p.units(), p.atin(),
                            p.seller())).toList(),
                    new SplitResponse(f.door(), f.pickup(), f.doorPct(), f.pickupPct()),
                    s.placements().stream().map(p -> new PlacementResponse(p.key(), p.name(), p.creative(),
                            p.impressions() == null ? 0 : p.impressions(), p.clicks() == null ? 0 : p.clicks(),
                            p.ctrPct(), p.orders())).toList());
        }
    }

    // ---- staff: settings ----------------------------------------------------------------------------------------

    @Schema(name = "PreorderPaymentSettings", description = """
            Fees per currency in minor units (0–10000), at least one payment method. Customers cancel for a full
            refund incl. the delivery fee until the window closes (cancelUntil CLOSE).""")
    record PaymentBody(@Schema(example = "{\"USD\": 799, \"CAD\": 999}") Map<String, Long> doorFee,
                       @Schema(example = "{\"USD\": 0}") Map<String, Long> pickupFee,
                       @Size(max = 40) @Schema(example = "10am–2pm") String pickupWindow,
                       @Schema(description = "CARD, WALLET (Apple Pay, Google Pay, Link), ACH")
                       List<PaymentMethod> allowedMethods, @Valid LateAddBody lateAdd,
                       @Valid MissedDeliveryBody missedDelivery, CancelUntil cancelUntil) {

        static PaymentBody of(PaymentSettings p) {
            return new PaymentBody(p.doorFees(), p.pickupFees(), p.pickupWindow(), sorted(p.allowedMethods()),
                    new LateAddBody(p.lateAdd().savedCard(), p.lateAdd().paymentLink(), p.lateAdd().linkExpiryHours()),
                    new MissedDeliveryBody(p.missedDelivery().offerRefund(), p.missedDelivery().offerNewDate()),
                    p.cancelUntil());
        }

        PaymentSettings toDomain(PaymentSettings current) {
            return new PaymentSettings(doorFee == null ? current.doorFees() : doorFee,
                    pickupFee == null ? current.pickupFees() : pickupFee,
                    allowedMethods == null ? current.allowedMethods() : Set.copyOf(allowedMethods),
                    cancelUntil == null ? current.cancelUntil() : cancelUntil,
                    lateAdd == null ? current.lateAdd()
                            : new LateAddSettings(lateAdd.savedCard(), lateAdd.paymentLink(), lateAdd.linkExpiryHours()),
                    missedDelivery == null ? current.missedDelivery()
                            : new MissedDeliverySettings(missedDelivery.offerRefund(), missedDelivery.offerNewDate()),
                    pickupWindow == null ? current.pickupWindow() : pickupWindow);
        }
    }

    record LateAddBody(boolean savedCard, boolean paymentLink,
                       @Min(value = 1, message = "Payment links last at least 1 hour")
                       @Max(value = 24, message = "Payment links expire after at most 24 hours (orders-service's limit)")
                       @Schema(description = "Payment link lifetime, 1–24 hours (orders-service's limit)")
                       int linkExpiryHours) {
    }

    record MissedDeliveryBody(boolean offerRefund, boolean offerNewDate) {
    }

    @Schema(name = "PreorderSettings", description = "Platform settings; the ETag carries the version for If-Match")
    record SettingsResponse(boolean enabled, Fallback fallback,
                            @Schema(description = "ribbon, hero, badge, pdp, cart, family") Map<String, Boolean>
                                    placements, PaymentBody payment, long version, Instant updatedAt) {

        static SettingsResponse of(PreorderSettings s) {
            return new SettingsResponse(s.enabled(), s.fallback(), placementsOf(s), PaymentBody.of(s.payment()),
                    s.version(), s.updatedAt());
        }
    }

    @Schema(name = "PreorderSettingsRequest", description = "Null fields keep their value")
    record SettingsRequest(Boolean enabled, Fallback fallback, Map<String, Boolean> placements,
                           @Valid PaymentBody payment) {

        SettingsCommand toCommand(PreorderSettings current) {
            Set<Placement> on = null;
            if (placements != null) {
                Map<Placement, Boolean> merged = new EnumMap<>(Placement.class);
                for (Placement p : Placement.values()) {
                    merged.put(p, current.placementOn(p));
                }
                placements.forEach((k, v) -> merged.put(placement(k), Boolean.TRUE.equals(v)));
                on = EnumSet.noneOf(Placement.class);
                for (Map.Entry<Placement, Boolean> e : merged.entrySet()) {
                    if (e.getValue()) {
                        on.add(e.getKey());
                    }
                }
            }
            return new SettingsCommand(enabled, fallback, on,
                    payment == null ? null : payment.toDomain(current.payment()));
        }
    }

    @Schema(name = "PreorderAreaRequest")
    record AreaRequest(@NotBlank @Schema(example = "US/TX/Lubbock") String key) {
    }

    @Schema(name = "PreorderAreaToggleRequest", description = "Keys: a country (US), a region (US/TX) or an area")
    record AreaToggleRequest(@NotEmpty @Size(max = 500) List<String> keys, @NotNull Boolean on) {
    }

    // ---- public and service ------------------------------------------------------------------------------------

    @Schema(name = "PreorderPublicWindow", description = "What shoppers see of a window (no PII)")
    record PublicWindow(UUID id, String code, String name, String where, String ribbon, String headline,
                        Instant opensAt, Instant closesAt, LocalDate deliversOn, String timeZone,
                        List<String> categories, boolean door, boolean pickup, String pickupAddress,
                        Map<String, PublicCreative> creatives) {

        static PublicWindow of(PreorderWindow w) {
            WindowDetails d = w.details();
            Map<String, PublicCreative> creatives = new LinkedHashMap<>();
            w.creatives().forEach((slot, c) -> creatives.put(slot.name().toLowerCase(Locale.ROOT),
                    new PublicCreative(c.url(), c.width(), c.height())));
            return new PublicWindow(w.id(), w.code(), d.name(), whereOf(d.targets()), d.ribbon(), d.headline(),
                    d.opensAt(), d.closesAt(), d.deliversOn(), d.timeZone().getId(),
                    d.categories().stream().sorted().toList(), d.door(), d.pickup(), d.pickupAddress(), creatives);
        }
    }

    record PublicCreative(String url, int width, int height) {
    }

    record PlaceResponse(String key, String country, String region, String city) {
    }

    @Schema(name = "PreorderFees", description = "Effective fees in minor units (null = option not offered)")
    record FeesResponse(Long doorFeeMinor, Long pickupFeeMinor, String currency, String pickupWindow) {
    }

    @Schema(name = "PreorderResolution", description = """
            What the storefront shows for a delivery place: on = a window covers it, the master switch and the area
            are on and the window is active. stage OPEN | UPCOMING | CLOSED; countdownTarget is closesAt when open,
            opensAt when upcoming and the delivery day when closed. When on is false the fallback applies (SHIP or
            WAITLIST); metro cities never show pre-order.""")
    record ResolveResponse(PlaceResponse place, boolean on, PreorderStatus stage, boolean metro, Fallback fallback,
                           Map<String, Boolean> placements, PublicWindow window, Instant countdownTarget,
                           @Schema(description = "Live pre-order count (when shown)") Integer count,
                           FeesResponse fees, List<PaymentMethod> paymentMethods, CancelUntil cancelUntil) {

        static ResolveResponse of(Resolution r) {
            PreorderSettings s = r.settings();
            FeesResponse fees = r.window() == null ? null : new FeesResponse(minor(r.doorFee()), minor(r.pickupFee()),
                    r.window().details().currency(), s.payment().pickupWindow());
            return new ResolveResponse(new PlaceResponse(r.place().key(), r.place().country(), r.place().region(),
                    r.place().city()), r.on(), r.stage(), r.metro(), s.fallback(), placementsOf(s),
                    r.window() == null ? null : PublicWindow.of(r.window()), r.countdownTarget(), r.count(), fees,
                    sorted(s.payment().allowedMethods()), s.payment().cancelUntil());
        }
    }

    @Schema(name = "PreorderOpenWindow", description = "An open or upcoming window for the /pre-orders page")
    record OpenWindowResponse(UUID id, String code, String name, String where, PreorderStatus stage, boolean open,
                              String city, String region, String country, String heroUrl, Instant opensAt,
                              Instant closesAt, LocalDate deliversOn,
                              @Schema(example = "Order by Oct 22 · delivered Oct 25") String label,
                              Long doorFeeMinor, Long pickupFeeMinor, String currency) {

        static OpenWindowResponse of(OpenWindow o) {
            PreorderWindow w = o.window();
            WindowDetails d = w.details();
            AreaKey first = d.targets().isEmpty() ? null : d.targets().get(0);
            CreativeRef hero = w.creatives().get(CreativeSlot.HERO);
            boolean open = o.stage() == PreorderStatus.OPEN;
            String delivered = " · delivered " + SHORT.format(d.deliversOn());
            String label = open ? "Order by " + SHORT.format(d.closesAt().atZone(d.timeZone())) + delivered
                    : "Opens " + SHORT.format(d.opensAt().atZone(d.timeZone())) + delivered;
            return new OpenWindowResponse(w.id(), w.code(), d.name(), whereOf(d.targets()), o.stage(), open,
                    first == null ? null : first.city(), first == null ? null : first.region(),
                    first == null ? null : first.country(), hero == null ? null : hero.url(), d.opensAt(),
                    d.closesAt(), d.deliversOn(), label, minor(o.doorFee()), minor(o.pickupFee()), d.currency());
        }
    }

    @Schema(name = "PreorderEligibilityItem", description = """
            One product. Window fields are set when preorder is true: orders-service builds the PREORDER package from
            them (fees in minor units, the payment methods the checkout may use).""")
    record EligibilityItem(String atin, boolean preorder, UUID windowId, String windowCode, String windowName,
                           PreorderStatus stage, LocalDate deliversOn, Instant closesAt, String timeZone,
                           Boolean door, Boolean pickup, Long doorFeeMinor, Long pickupFeeMinor, String currency,
                           List<PaymentMethod> allowedMethods, String pickupWindow, String pickupPointId) {

        static EligibilityItem of(Eligibility e, Resolution r) {
            PreorderWindow w = e.window();
            if (w == null) {
                return new EligibilityItem(e.atin(), false, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null);
            }
            WindowDetails d = w.details();
            PaymentSettings pay = r.settings().payment();
            return new EligibilityItem(e.atin(), e.preorder(), w.id(), w.code(), d.name(), e.stage(), d.deliversOn(),
                    d.closesAt(), d.timeZone().getId(), d.door(), d.pickup(), minor(w.doorFee(pay).orElse(null)),
                    w.pickupFee(pay).minor(), d.currency(), sorted(pay.allowedMethods()), pay.pickupWindow(),
                    d.pickupPointId());
        }
    }

    @Schema(name = "PreorderEligibility", description = """
            Per-ATIN pre-order eligibility at a place (orders-service). A product is pre-order where pre-order is on,
            its catalog category is in the window's categories and it is not flagged shipsNow. fees are what orders
            charges for the PREORDER package (DOOR or PICKUP); lines of an UPCOMING or CLOSED window block checkout.""")
    record EligibilityResponse(PlaceResponse place, boolean on, PreorderStatus stage, UUID windowId,
                               LocalDate deliversOn, FeesResponse fees, List<EligibilityItem> items) {

        static EligibilityResponse of(EligibilityResult r) {
            Resolution res = r.resolution();
            FeesResponse fees = res.window() == null ? null : new FeesResponse(minor(res.doorFee()),
                    minor(res.pickupFee()), res.window().details().currency(),
                    res.settings().payment().pickupWindow());
            return new EligibilityResponse(new PlaceResponse(res.place().key(), res.place().country(),
                    res.place().region(), res.place().city()), res.on(), res.stage(),
                    res.window() == null ? null : res.window().id(),
                    res.window() == null ? null : res.window().details().deliversOn(), fees,
                    r.items().stream().map(e -> EligibilityItem.of(e, res)).toList());
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    static String statusLabelOf(PreorderStatus s, boolean areaOff) {
        if (areaOff && s != PreorderStatus.DELIVERED && s != PreorderStatus.DRAFT) {
            return "Area off";
        }
        return switch (s) {
            case OPEN -> "Open";
            case UPCOMING -> "Scheduled";
            case CLOSED -> "Closed · delivering";
            case DELIVERED -> "Delivered";
            case DRAFT -> "Draft";
            case PAUSED -> "Paused";
        };
    }

    /** {@code locName} of the design: up to three labels, else two and "+ N more". */
    static String whereOf(List<AreaKey> targets) {
        if (targets.isEmpty()) {
            return "No area chosen";
        }
        List<String> names = targets.stream().map(AreaKey::label).toList();
        return names.size() <= 3 ? String.join(" · ", names)
                : String.join(" · ", names.subList(0, 2)) + " + " + (names.size() - 2) + " more";
    }

    static Map<String, Boolean> placementsOf(PreorderSettings s) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (Placement p : Placement.values()) {
            m.put(p.name().toLowerCase(Locale.ROOT), s.placementOn(p));
        }
        return m;
    }

    static Placement placement(String key) {
        try {
            return Placement.valueOf(key.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new RuleViolation("delivery-runs.invalid-placement",
                    "Placements are ribbon, hero, badge, pdp, cart and family");
        }
    }

    private static List<PaymentMethod> sorted(Set<PaymentMethod> methods) {
        return methods.stream().sorted().toList();
    }

    private static Long minor(Money m) {
        return m == null ? null : m.minor();
    }

    private static long orZero(Money m) {
        return m == null ? 0 : m.minor();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
