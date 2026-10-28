package com.afrotransact.deliveryruns.adapter.in.web;

import java.util.List;
import java.util.UUID;

import com.afrotransact.deliveryruns.adapter.in.web.PreorderDtos.EligibilityResponse;
import com.afrotransact.deliveryruns.adapter.in.web.PreorderDtos.OpenWindowResponse;
import com.afrotransact.deliveryruns.adapter.in.web.PreorderDtos.ResolveResponse;
import com.afrotransact.deliveryruns.application.PreorderAudienceService;
import com.afrotransact.deliveryruns.application.PreorderAudienceService.AudiencePageView;
import com.afrotransact.deliveryruns.application.PreorderPublicService;
import com.afrotransact.deliveryruns.domain.Channel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Public pre-orders", description = "No sign-in: what the storefront shows for a delivery place; "
        + "service: per-product eligibility for orders-service")
@RestController
class PublicPreorderController {

    private final PreorderPublicService preorders;
    private final PreorderAudienceService audience;

    PublicPreorderController(PreorderPublicService preorders, PreorderAudienceService audience) {
        this.preorders = preorders;
        this.audience = audience;
    }

    @Operation(summary = "Resolve the pre-order for a delivery place (no sign-in, no PII)",
            description = "Windows that are not drafts and cover the place; the first OPEN, else UPCOMING, else CLOSED "
                    + "one. on = window found && master switch && window active && area on. Metro cities never show "
                    + "pre-order. Fees are the effective door / pickup fees.")
    @SecurityRequirements
    @GetMapping("/v1/public/preorders/resolve")
    ResolveResponse resolve(@RequestParam String country, @RequestParam String region,
                            @RequestParam(required = false) String city) {
        return ResolveResponse.of(preorders.resolve(country, region, city));
    }

    @Operation(summary = "Open and upcoming pre-orders for the /pre-orders page (no sign-in)",
            description = "Published, active windows with at least one target area on; open first, then by name. "
                    + "Empty when pre-orders are switched off.")
    @SecurityRequirements
    @GetMapping("/v1/public/preorders/open")
    List<OpenWindowResponse> open() {
        return preorders.open().stream().map(OpenWindowResponse::of).toList();
    }

    @Operation(summary = "Pre-order eligibility of products at a place (delivery-runs:reserve, orders-service)",
            description = "atins: comma-separated or repeated (1–100). A product is pre-order where pre-order is on, "
                    + "its catalog category is in the window's categories and it is not flagged shipsNow (catalog "
                    + "flags preorderEligible / shipsNow). Returns the effective fees orders-service charges for "
                    + "the PREORDER package. earlyAccessHours (S323, a member's early access): a window opening "
                    + "within that time is reported OPEN. 502 when catalog is unavailable.")
    @GetMapping("/v1/service/preorders/eligibility")
    EligibilityResponse eligibility(@RequestParam String country, @RequestParam String region,
                                    @RequestParam(required = false) String city,
                                    @RequestParam List<String> atins,
                                    @RequestParam(defaultValue = "0") int earlyAccessHours) {
        return EligibilityResponse.of(preorders.eligibility(country, region, city, atins,
                Math.max(0, Math.min(earlyAccessHours, 720))));
    }

    @Operation(summary = "A pre-order window's audience on a channel (delivery-runs:audience.read, notifications)",
            description = "segment SUBSCRIBERS: subscribers of the delivery-runs locations in the window's targets "
                    + "with consent on the channel, plus past pre-order buyers there (subscriptionId buyer:<id>; "
                    + "notifications applies its SMS consent and preferences). segment WAITLIST: waitlist sign-ups of "
                    + "the window's areas (EMAIL only). Cursor paging: send next back as after. 404 for an unknown "
                    + "window.")
    @GetMapping("/v1/service/preorders/{windowId}/audience")
    AudiencePageView audience(@PathVariable UUID windowId, @RequestParam Channel channel,
                              @RequestParam(defaultValue = "SUBSCRIBERS") String segment,
                              @RequestParam(required = false) String after,
                              @RequestParam(defaultValue = "500") int limit) {
        return audience.audience(windowId, channel, segment, after, limit);
    }
}
