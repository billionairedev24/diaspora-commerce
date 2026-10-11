package com.afrotransact.deliveryruns.adapter.in.web;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.afrotransact.deliveryruns.adapter.in.web.PreorderDtos.BulkSwitchRequest;
import com.afrotransact.deliveryruns.adapter.in.web.PreorderDtos.BulkSwitchResponse;
import com.afrotransact.deliveryruns.adapter.in.web.PreorderDtos.CreativeRequest;
import com.afrotransact.deliveryruns.adapter.in.web.PreorderDtos.LateOrderRequest;
import com.afrotransact.deliveryruns.adapter.in.web.PreorderDtos.OrderPageResponse;
import com.afrotransact.deliveryruns.adapter.in.web.PreorderDtos.OrderResponse;
import com.afrotransact.deliveryruns.adapter.in.web.PreorderDtos.ProductListResponse;
import com.afrotransact.deliveryruns.adapter.in.web.PreorderDtos.SummaryResponse;
import com.afrotransact.deliveryruns.adapter.in.web.PreorderDtos.SwitchRequest;
import com.afrotransact.deliveryruns.adapter.in.web.PreorderDtos.WindowPageResponse;
import com.afrotransact.deliveryruns.adapter.in.web.PreorderDtos.WindowRequest;
import com.afrotransact.deliveryruns.adapter.in.web.PreorderDtos.WindowResponse;
import com.afrotransact.deliveryruns.application.CsvExport;
import com.afrotransact.deliveryruns.application.ExportKind;
import com.afrotransact.deliveryruns.application.OrderFilter;
import com.afrotransact.deliveryruns.application.PreorderOrderService;
import com.afrotransact.deliveryruns.application.PreorderWindowService;
import com.afrotransact.deliveryruns.application.WindowView;
import com.afrotransact.deliveryruns.domain.AttributionChannel;
import com.afrotransact.deliveryruns.domain.CreativeSlot;
import com.afrotransact.deliveryruns.domain.PreorderOrderStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Tag(name = "Pre-orders", description = "Staff: pre-order windows, creatives, orders, analytics, exports and late adds "
        + "(delivery-runs:read to view; preorders:manage, preorders:export, preorders:late-add)")
@RestController
class PreorderController {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final PreorderWindowService windows;
    private final PreorderOrderService orders;

    PreorderController(PreorderWindowService windows, PreorderOrderService orders) {
        this.windows = windows;
        this.orders = orders;
    }

    @Operation(summary = "List pre-orders with tab counts (delivery-runs:read)",
            description = "status: all, open, upcoming, closed, draft (tabs) or a status (OPEN, UPCOMING, CLOSED, "
                    + "DELIVERED, DRAFT, PAUSED); q searches name, code and areas. counts per tab; enabled = master "
                    + "switch.")
    @GetMapping("/v1/preorders")
    WindowPageResponse list(@RequestParam(required = false) String status, @RequestParam(required = false) String q,
                            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return WindowPageResponse.of(windows.list(status, q, page, size));
    }

    @Operation(summary = "Open a pre-order (DRAFT) (preorders:manage)",
            description = "The code (e.g. LUB-7) is the first 3 letters of the first target city plus a counter.")
    @PostMapping("/v1/preorders")
    ResponseEntity<WindowResponse> create(@Valid @RequestBody WindowRequest body) {
        WindowView v = windows.create(body.toCommand());
        return Versions.created(WindowResponse.of(v), v.window().version());
    }

    @Operation(summary = "A pre-order with its checks, fees, creatives and counts (delivery-runs:read)",
            description = "ETag carries the version to send back in If-Match.")
    @GetMapping("/v1/preorders/{id}")
    ResponseEntity<WindowResponse> get(@PathVariable UUID id) {
        return tagged(windows.get(id));
    }

    @Operation(summary = "Edit a pre-order (preorders:manage)",
            description = "If-Match with the ETag; 412 delivery-runs.version-mismatch when it changed since. A "
                    + "published pre-order must keep passing its checks (422 delivery-runs.preorder-checks-failed); "
                    + "a delivered one is read-only.")
    @PutMapping("/v1/preorders/{id}")
    ResponseEntity<WindowResponse> update(@PathVariable UUID id, @Valid @RequestBody WindowRequest body,
                                          @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                                          String ifMatch) {
        return tagged(windows.update(id, body.toCommand(), Versions.expected(ifMatch)));
    }

    @Operation(summary = "Publish a draft (preorders:manage)",
            description = "All checks must pass: name set, at least one area, closes after opens, delivers on or "
                    + "after close (−12 h), storefront hero uploaded, ribbon text set, door or pickup, and a door "
                    + "fee for the window's currency. 422 delivery-runs.preorder-checks-failed lists violations[] and "
                    + "errors{check: label} (keys name, area, dates, delivery, hero, ribbon, fulfilment, doorFee).")
    @PostMapping("/v1/preorders/{id}/publish")
    ResponseEntity<WindowResponse> publish(@PathVariable UUID id,
                                           @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                                           String ifMatch) {
        return tagged(windows.publish(id, Versions.expected(ifMatch)));
    }

    @Operation(summary = "Pause a published pre-order (placements and comms stop) (preorders:manage)")
    @PostMapping("/v1/preorders/{id}/pause")
    ResponseEntity<WindowResponse> pause(@PathVariable UUID id,
                                         @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                                         String ifMatch) {
        return tagged(windows.pause(id, Versions.expected(ifMatch)));
    }

    @Operation(summary = "Resume a paused pre-order (preorders:manage)",
            description = "The publish checks must still pass (422 delivery-runs.preorder-checks-failed).")
    @PostMapping("/v1/preorders/{id}/resume")
    ResponseEntity<WindowResponse> resume(@PathVariable UUID id,
                                          @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                                          String ifMatch) {
        return tagged(windows.resume(id, Versions.expected(ifMatch)));
    }

    @Operation(summary = "Switch one pre-order on or off (preorders:manage)",
            description = "Sets this window's own switch only; the master switch is never touched. The window is live "
                    + "when the master switch AND its own switch AND one of its areas are on (switchState says which "
                    + "holds it). Off stops resolution, banners, eligibility and the cart's pre-order packages; orders "
                    + "already placed are kept. On re-runs the publish checks (422 delivery-runs.preorder-checks-failed). "
                    + "Idempotent; drafts and delivered windows answer 409. If-Match optional; publishes "
                    + "preorder.window.paused / resumed with the actor when it flips.")
    @PatchMapping("/v1/preorders/{id}")
    ResponseEntity<WindowResponse> setActive(@PathVariable UUID id, @Valid @RequestBody SwitchRequest body,
                                             @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                                             String ifMatch) {
        return tagged(windows.setActive(id, body.active(), Versions.expected(ifMatch)));
    }

    @Operation(summary = "Switch several pre-orders on or off (preorders:manage)",
            description = "1–100 ids, each switched on its own as PATCH /v1/preorders/{id}: SWITCHED, UNCHANGED or "
                    + "SKIPPED (draft, delivered, unknown or failing its publish checks, with the reason).")
    @PatchMapping("/v1/preorders")
    BulkSwitchResponse setActive(@Valid @RequestBody BulkSwitchRequest body) {
        return BulkSwitchResponse.of(windows.setActive(body.ids(), body.active()));
    }

    @Operation(summary = "Attach an uploaded image to a creative slot (preorders:manage)",
            description = "Verified with media-service (READY image); width and height are stored and a ratio more "
                    + "than 12 % off the slot spec is flagged RATIO_OFF (warning). 502 when media is unavailable.")
    @PutMapping("/v1/preorders/{id}/creatives/{slot}")
    ResponseEntity<WindowResponse> setCreative(@PathVariable UUID id, @PathVariable CreativeSlot slot,
                                               @Valid @RequestBody CreativeRequest body,
                                               @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                                               String ifMatch) {
        return tagged(windows.setCreative(id, slot, body.assetId(), Versions.expected(ifMatch)));
    }

    @Operation(summary = "Remove a creative (a published pre-order keeps its hero) (preorders:manage)")
    @DeleteMapping("/v1/preorders/{id}/creatives/{slot}")
    ResponseEntity<WindowResponse> removeCreative(@PathVariable UUID id, @PathVariable CreativeSlot slot,
                                                  @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                                                  String ifMatch) {
        return tagged(windows.removeCreative(id, slot, Versions.expected(ifMatch)));
    }

    @Operation(summary = "Orders of a pre-order, newest first (delivery-runs:read)",
            description = "q searches order number, customer, phone, address and zip.")
    @GetMapping("/v1/preorders/{id}/orders")
    OrderPageResponse orders(@PathVariable UUID id, @RequestParam(required = false) PreorderOrderStatus status,
                             @RequestParam(required = false) AttributionChannel channel,
                             @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "12") int size) {
        return OrderPageResponse.of(orders.orders(id, new OrderFilter(status, channel, q), page, size));
    }

    @Operation(summary = "KPIs, orders by day, by channel, top products, door/pickup split, placements "
            + "(delivery-runs:read)")
    @GetMapping("/v1/preorders/{id}/summary")
    SummaryResponse summary(@PathVariable UUID id) {
        return SummaryResponse.of(orders.summary(id));
    }

    @Operation(summary = "Products customer service can add late: the window's categories (delivery-runs:read)",
            description = "Live products of the window's categories in its market (search-service); price in minor "
                    + "units. 502 when search is unavailable.")
    @GetMapping("/v1/preorders/{id}/products")
    ProductListResponse products(@PathVariable UUID id) {
        return ProductListResponse.of(orders.products(id));
    }

    @Operation(summary = "CSV export (preorders:export)",
            description = "kind: orders, orders-filtered (takes the orders filter), picking-list, "
                    + "advertorial-report. Audit event preorder.export.created.",
            responses = @ApiResponse(responseCode = "200", description = "CSV",
                    content = @Content(mediaType = "text/csv", schema = @Schema(type = "string"))))
    @GetMapping("/v1/preorders/{id}/exports/{kind:[a-z-]+}.csv")
    ResponseEntity<StreamingResponseBody> export(@PathVariable UUID id, @PathVariable String kind,
                                                 @RequestParam(required = false) PreorderOrderStatus status,
                                                 @RequestParam(required = false) AttributionChannel channel,
                                                 @RequestParam(required = false) String q) {
        CsvExport csv = orders.export(id, ExportKind.fromPath(kind), new OrderFilter(status, channel, q));
        StreamingResponseBody body = out -> {
            Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            csv.writeTo(writer);
        };
        return ResponseEntity.ok().contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(csv.filename()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    @Operation(summary = "Add a late order for a customer (preorders:late-add)",
            description = "OPEN (assisted) or CLOSED pre-orders; 422 once delivered. Placed through orders-service "
                    + "(saved card off-session or SMS payment link); recorded as LATE_ADD with channel AGENT_LATE "
                    + "and returned as the projected order (with paymentLinkUrl / ordersStatus). Idempotency-Key "
                    + "makes retries safe.")
    @PostMapping("/v1/preorders/{id}/late-orders")
    ResponseEntity<OrderResponse> addLateOrder(@PathVariable UUID id, @Valid @RequestBody LateOrderRequest body,
                                                   @RequestHeader(name = "Idempotency-Key", required = false)
                                                   String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(OrderResponse.of(orders.addLateOrder(id, body.toCommand(idempotencyKey))));
    }

    private static ResponseEntity<WindowResponse> tagged(WindowView v) {
        return Versions.tagged(WindowResponse.of(v), v.window().version());
    }
}
