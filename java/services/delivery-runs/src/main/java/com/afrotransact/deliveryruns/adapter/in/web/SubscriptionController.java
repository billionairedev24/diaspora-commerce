package com.afrotransact.deliveryruns.adapter.in.web;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.deliveryruns.adapter.in.web.PublicDtos.AcceptedResponse;
import com.afrotransact.deliveryruns.adapter.in.web.PublicDtos.EmailSubscriptionRequest;
import com.afrotransact.deliveryruns.adapter.in.web.PublicDtos.MySubscriptionRequest;
import com.afrotransact.deliveryruns.adapter.in.web.PublicDtos.SubscriptionResponse;
import com.afrotransact.deliveryruns.adapter.in.web.PublicDtos.TokenRequest;
import com.afrotransact.deliveryruns.adapter.in.web.PublicDtos.UnsubscribeRequest;
import com.afrotransact.deliveryruns.application.PreorderAudienceService;
import com.afrotransact.deliveryruns.application.SubscriptionService;
import com.afrotransact.deliveryruns.domain.Channel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Subscriptions", description = "Run alerts per location with per-channel consent (S262)")
@RestController
class SubscriptionController {

    private final SubscriptionService subscriptions;
    private final PreorderAudienceService preorderAudience;

    SubscriptionController(SubscriptionService subscriptions, PreorderAudienceService preorderAudience) {
        this.subscriptions = subscriptions;
        this.preorderAudience = preorderAudience;
    }

    @Operation(summary = "Subscribe by email (no sign-in): location run alerts or a pre-order waitlist",
            description = "Always 202, whether or not the address was known. With locationId: notifications sends "
                    + "the confirmation link from delivery-runs.subscription.created; nothing else is sent until it "
                    + "is used (status CONFIRMATION_SENT). Without locationId: joins the pre-order waitlist of area "
                    + "(or country / region / city), source defaults to preorder-waitlist; the opening notice "
                    + "(preorder.waitlist.opened) carries a one-click leave link (status WAITLISTED).")
    @SecurityRequirements
    @PostMapping("/v1/public/subscriptions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    AcceptedResponse subscribeEmail(@Valid @RequestBody EmailSubscriptionRequest body) {
        if (body.locationId() == null) {
            preorderAudience.joinWaitlist(body.email(), body.area(), body.country(), body.region(), body.city(),
                    body.source());
            return new AcceptedResponse("WAITLISTED");
        }
        subscriptions.subscribeEmail(body.locationId(), body.email(), body.source());
        return new AcceptedResponse("CONFIRMATION_SENT");
    }

    @Operation(summary = "Leave a pre-order waitlist (token from the opening notice)")
    @SecurityRequirements
    @PostMapping("/v1/public/preorders/waitlist/unsubscribe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void leaveWaitlist(@Valid @RequestBody TokenRequest body) {
        preorderAudience.leaveWaitlist(body.token());
    }

    @Operation(summary = "Confirm an email subscription (token from the confirmation link)")
    @SecurityRequirements
    @PostMapping("/v1/public/subscriptions/confirm")
    SubscriptionResponse confirm(@Valid @RequestBody TokenRequest body) {
        return SubscriptionResponse.of(subscriptions.confirm(body.token()));
    }

    @Operation(summary = "Unsubscribe from a message link (token), per channel or all")
    @SecurityRequirements
    @PostMapping("/v1/public/subscriptions/unsubscribe")
    SubscriptionResponse unsubscribe(@Valid @RequestBody UnsubscribeRequest body) {
        return SubscriptionResponse.of(subscriptions.unsubscribe(body.token(), body.channels()));
    }

    @Operation(summary = "Signed-in shopper: my run subscriptions")
    @GetMapping("/v1/me/run-subscriptions")
    List<SubscriptionResponse> mine() {
        return subscriptions.mine().stream().map(SubscriptionResponse::of).toList();
    }

    @Operation(summary = "Signed-in shopper: subscribe to a location (or add channels); active at once")
    @PostMapping("/v1/me/run-subscriptions")
    SubscriptionResponse subscribe(@Valid @RequestBody MySubscriptionRequest body) {
        return SubscriptionResponse.of(subscriptions.subscribeMine(body.locationId(), body.channels(), body.source()));
    }

    @Operation(summary = "Signed-in shopper: withdraw channels for a location (no channel = all)")
    @DeleteMapping("/v1/me/run-subscriptions/{locationId}")
    SubscriptionResponse unsubscribeMine(@PathVariable UUID locationId,
                                         @RequestParam(required = false) Set<Channel> channel) {
        return SubscriptionResponse.of(subscriptions.unsubscribeMine(locationId, channel));
    }
}
