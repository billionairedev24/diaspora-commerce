package com.afrotransact.deliveryruns.adapter.in.web;

import java.util.UUID;

import com.afrotransact.deliveryruns.adapter.in.web.EconomicsDtos.ChangesResponse;
import com.afrotransact.deliveryruns.adapter.in.web.EconomicsDtos.PlanRequest;
import com.afrotransact.deliveryruns.adapter.in.web.EconomicsDtos.SettingsRequest;
import com.afrotransact.deliveryruns.adapter.in.web.EconomicsDtos.SettingsResponse;
import com.afrotransact.deliveryruns.application.EconomicsService;
import com.afrotransact.deliveryruns.application.EconomicsSettingsService;
import com.afrotransact.deliveryruns.domain.EconomicsRevision;
import com.afrotransact.deliveryruns.domain.RunPnl;
import com.afrotransact.deliveryruns.domain.WindowEconomics;
import com.afrotransact.deliveryruns.domain.WindowPlan;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Economics", description = "Staff: run and window P&L, break-even for a window being created, and the "
        + "driver cost model behind them (delivery-runs:read to view; preorders:manage to change the cost model)")
@RestController
class EconomicsController {

    private final EconomicsService economics;
    private final EconomicsSettingsService settings;

    EconomicsController(EconomicsService economics, EconomicsSettingsService settings) {
        this.economics = economics;
        this.settings = settings;
    }

    @Operation(summary = "A run's P&L before and after it (delivery-runs:read)",
            description = "Built from the run's real orders. BEFORE: the orders reserved so far. AFTER: the completed run: "
                    + "the orders delivered, those cancelled after reserving shown apart, the delivery fees each order was "
                    + "actually charged (door, pickup and carrier differ), what was refunded, the cost (route estimate, or "
                    + "the minutes the driver was out once captured), the contribution margin and the break-even at the fee "
                    + "the orders paid. CANCELLED: nothing earned or spent; ordersRefunded and refundedFeesMinor (the fees "
                    + "the orders were charged, an estimate only where refundedFeesEstimated) say what went back to "
                    + "customers. platformFees is a separate lens, never part of the delivery P&L: what the platform keeps "
                    + "on the goods (commission payments reported, else the staff-configured fee per order). "
                    + "commissionShareMinor types that figure in for this view (source MANUAL).")
    @GetMapping("/v1/runs/{id}/pnl")
    RunPnl runPnl(@PathVariable UUID id, @RequestParam(required = false) Long commissionShareMinor) {
        return economics.runPnl(id, commissionShareMinor);
    }

    @Operation(summary = "A pre-order window's economics (delivery-runs:read)",
            description = "Orders, revenue, delivery fees collected, the estimated cost of the run that delivers it, "
                    + "contribution margin and break-even orders. A cancelled window reports the orders refunded "
                    + "(ordersRefunded, refundedMinor) instead of revenue. platformFees is the platform's fees on the "
                    + "goods, apart from the delivery P&L; commissionShareMinor types it in for this view.")
    @GetMapping("/v1/preorders/{id}/economics")
    WindowEconomics windowEconomics(@PathVariable UUID id, @RequestParam(required = false) Long commissionShareMinor) {
        return economics.windowEconomics(id, commissionShareMinor);
    }

    @Operation(summary = "Break-even for a window being created or edited (delivery-runs:read)",
            description = "The cost of the run that would deliver it, the orders needed to break even, and whether "
                    + "the minimum reaches them. economics is null when no active location serves the targets or "
                    + "geo-service cannot measure the route. economics.atCapacity is the margin when the run is full: at "
                    + "capacityOrders if asked, else the location's default capacity (null when that is not limited).")
    @PostMapping("/v1/preorders/economics-preview")
    WindowPlan planWindow(@Valid @RequestBody PlanRequest body) {
        return economics.planWindow(body.toCommand());
    }

    @Operation(summary = "The driver cost model behind run economics (delivery-runs:read)",
            description = "Version 0 (source CONFIG) until staff save; then the saved numbers (source STAFF). The "
                    + "ETag carries the version.")
    @GetMapping("/v1/economics-settings")
    ResponseEntity<SettingsResponse> economicsSettings() {
        EconomicsRevision current = settings.current();
        return Versions.tagged(SettingsResponse.of(current), current.version());
    }

    @Operation(summary = "Change the driver cost model (preorders:manage)",
            description = "Makes the next version. A reason is required (422 delivery-runs.reason-required) and is "
                    + "logged with who and when; numbers out of range are 422 delivery-runs.invalid-economics. "
                    + "If-Match is the version seen (0 for the defaults; 412 delivery-runs.version-mismatch when it "
                    + "changed). A save that changes nothing makes no new version. Runs and windows are priced "
                    + "with the new numbers from the next calculation.")
    @PutMapping("/v1/economics-settings")
    ResponseEntity<SettingsResponse> replaceEconomicsSettings(@Valid @RequestBody SettingsRequest body,
                                             @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                                             String ifMatch) {
        EconomicsRevision saved = settings.replace(body.toCommand(), Versions.expected(ifMatch));
        return Versions.tagged(SettingsResponse.of(saved), saved.version());
    }

    @Operation(summary = "Audit trail of the driver cost model, newest first (delivery-runs:read)",
            description = "Every save with the numbers before and after, who changed them, when and why.")
    @GetMapping("/v1/economics-settings/changes")
    ChangesResponse economicsSettingsChanges(@RequestParam(defaultValue = "50") int limit) {
        return ChangesResponse.of(settings.changes(limit));
    }
}
