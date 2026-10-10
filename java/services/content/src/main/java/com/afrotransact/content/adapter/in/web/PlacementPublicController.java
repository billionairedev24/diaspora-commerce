package com.afrotransact.content.adapter.in.web;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.afrotransact.content.adapter.in.web.PlacementDtos.BundleResponse;
import com.afrotransact.content.adapter.in.web.PlacementDtos.BundledPlacement;
import com.afrotransact.content.adapter.in.web.PlacementDtos.ResolveResponse;
import com.afrotransact.content.adapter.in.web.PlacementDtos.ServedPlacement;
import com.afrotransact.content.application.ContentSettings;
import com.afrotransact.content.application.PlacementDeliveryService;
import com.afrotransact.content.application.PlacementDeliveryService.PlacementBundle;
import com.afrotransact.content.application.PlacementDeliveryService.ResolvedPlacements;
import com.afrotransact.content.domain.placement.Device;
import com.afrotransact.content.domain.placement.PlacementContext;
import com.afrotransact.content.domain.placement.SlotKey;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Placement delivery", description = "Public, cacheable placements for storefront, apps and the CDN")
@SecurityRequirements
@RestController
@RequestMapping("/v1/placements")
class PlacementPublicController {

    private final PlacementDeliveryService delivery;
    private final ContentSettings settings;

    PlacementPublicController(PlacementDeliveryService delivery, ContentSettings settings) {
        this.delivery = delivery;
        this.settings = settings;
    }

    @Operation(summary = "Live placements for a context (ETag; 304 on If-None-Match)",
            description = "Highest priority first, at most the slot's maxItems per slot. Pass `seen` (id:count,…) to "
                    + "apply frequency caps server-side; such responses are private.")
    @GetMapping("/resolve")
    ResponseEntity<ResolveResponse> resolve(
            @RequestParam String market,
            @RequestParam(required = false) @Parameter(example = "hero_carousel") String slot,
            @RequestParam(required = false) String zip,
            @RequestParam(required = false) String dma,
            @RequestParam(required = false) @Parameter(description = "DESKTOP, MOBILE, TABLET or APP") String device,
            @RequestParam(required = false) Boolean returning,
            @RequestParam(required = false) @Parameter(description = "Comma-separated community keys") String communities,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @Parameter(description = "Impressions per placement: plc_a:2,plc_b:1") String seen) {
        SlotKey slotKey = slot == null || slot.isBlank() ? null : SlotKey.fromKey(slot);
        PlacementContext ctx = new PlacementContext(market, slotKey, zip, dma, Device.parse(device), returning,
                csv(communities), category, seenCounts(seen));
        ResolvedPlacements r = delivery.resolve(ctx);
        CacheControl cache = r.personalised() ? CacheControl.noStore()
                : CacheControl.maxAge(settings.resolveMaxAge()).cachePublic();
        return ResponseEntity.ok().cacheControl(cache).eTag(r.etag())
                .header("Cache-Tag", "placements:" + r.market())
                .body(new ResolveResponse(r.market(), slotKey == null ? null : slotKey.key(),
                        r.placements().stream().map(ServedPlacement::of).toList()));
    }

    @Operation(summary = "Compiled per-market bundle for the CDN (short TTL, purged on publish)")
    @GetMapping("/bundles/{market}")
    ResponseEntity<BundleResponse> bundle(@PathVariable String market) {
        PlacementBundle b = delivery.bundle(market);
        Map<String, List<BundledPlacement>> slots = new LinkedHashMap<>();
        for (SlotKey key : SlotKey.values()) {
            slots.put(key.key(), b.slots().getOrDefault(key, List.of()).stream().map(BundledPlacement::of).toList());
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(settings.bundleMaxAge()).sMaxAge(settings.bundleSharedMaxAge()).cachePublic())
                .eTag(b.etag())
                .header("Cache-Tag", "placements:" + b.market())
                .body(new BundleResponse(b.market(), b.generatedAt(), b.etag(), slots));
    }

    private static Set<String> csv(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    private static Map<String, Integer> seenCounts(String raw) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String part : csv(raw)) {
            int colon = part.lastIndexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("seen entries look like plc_id:count");
            }
            out.put(part.substring(0, colon), Integer.parseInt(part.substring(colon + 1)));
        }
        return out;
    }
}
