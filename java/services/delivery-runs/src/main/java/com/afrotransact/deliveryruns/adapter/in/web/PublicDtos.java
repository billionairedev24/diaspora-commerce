package com.afrotransact.deliveryruns.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.deliveryruns.adapter.in.web.LocationDtos.AreaResponse;
import com.afrotransact.deliveryruns.adapter.in.web.LocationDtos.PickupPointBody;
import com.afrotransact.deliveryruns.application.AvailableRun;
import com.afrotransact.deliveryruns.application.LocationPage;
import com.afrotransact.deliveryruns.domain.Channel;
import com.afrotransact.deliveryruns.domain.ChannelConsent;
import com.afrotransact.deliveryruns.domain.DropMode;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.LocationDetails;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunTerms;
import com.afrotransact.deliveryruns.domain.Subscription;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Public (storefront, content) and shopper bodies. */
final class PublicDtos {

    private PublicDtos() {
    }

    @Schema(name = "PublicRun", description = "A run as shoppers see it")
    record PublicRunResponse(UUID runId, UUID locationId, String locationSlug, String town, String status,
                             Instant windowStart, Instant windowEnd, Instant cutoffAt, long feeMinor, String currency,
                             DropMode dropMode, PickupPointBody pickupPoint, boolean acceptingOrders,
                             @Schema(description = "Orders left (null = not limited)") Integer ordersLeft,
                             @Schema(description = "No room left; checkout offers the next run") boolean full,
                             String runUrl) {

        static PublicRunResponse of(Run r, Location l, boolean accepting, String runUrl) {
            RunTerms t = r.terms();
            return new PublicRunResponse(r.id(), l.id(), l.slug(), l.name(), r.status().name(), t.window().start(),
                    t.window().end(), t.cutoffAt(), t.feeMinor(), t.currency(), t.dropMode(),
                    PickupPointBody.of(t.pickupPoint()), accepting, t.capacity().ordersLeft(r.reservedOrders()),
                    r.isFull(1), runUrl);
        }

        static PublicRunResponse of(AvailableRun a) {
            return of(a.run(), a.location(), true, a.runUrl());
        }
    }

    @Schema(name = "AvailableRuns")
    record AvailableRunsResponse(List<PublicRunResponse> runs) {
    }

    @Schema(name = "PublicLocation")
    record PublicLocationResponse(UUID id, String slug, String name, String market, String state, String timeZone,
                                  AreaResponse area, long defaultFeeMinor, String currency, DropMode dropMode,
                                  PickupPointBody pickupPoint, String whatsappInviteUrl,
                                  Map<String, String> socialLinks) {

        static PublicLocationResponse of(Location l) {
            LocationDetails d = l.details();
            return new PublicLocationResponse(l.id(), d.slug(), d.name(), d.market(), d.state(), d.timeZone().getId(),
                    AreaResponse.of(d.area()), d.defaultFeeMinor(), d.currency(), d.dropMode(),
                    PickupPointBody.of(d.pickupPoint()), d.whatsappInviteUrl(), d.socialLinks());
        }
    }

    @Schema(name = "LocationPage", description = "Location page data: location, upcoming runs, share links")
    record LocationPageResponse(PublicLocationResponse location, List<PublicRunResponse> runs, String locationUrl,
                                Map<String, String> shareLinks) {

        static LocationPageResponse of(LocationPage p, Instant now, String runUrlBase) {
            return new LocationPageResponse(PublicLocationResponse.of(p.location()),
                    p.runs().stream().map(r -> PublicRunResponse.of(r, p.location(), r.acceptsOrders(now),
                            runUrlBase + r.id())).toList(),
                    p.locationUrl(), p.shareLinks());
        }
    }

    @Schema(name = "EmailSubscriptionRequest", description = "Anonymous sign-up: email alerts after double opt-in")
    record EmailSubscriptionRequest(@NotNull UUID locationId, @NotBlank @Email @Size(max = 254) String email,
                                    @Size(max = 80) @Schema(example = "location-page") String source) {
    }

    @Schema(name = "SubscriptionAccepted")
    record AcceptedResponse(@Schema(example = "CONFIRMATION_SENT") String status) {
    }

    @Schema(name = "TokenRequest")
    record TokenRequest(@NotBlank @Size(max = 200) String token) {
    }

    @Schema(name = "UnsubscribeRequest", description = "Channels to withdraw; empty or missing = all")
    record UnsubscribeRequest(@NotBlank @Size(max = 200) String token, Set<Channel> channels) {
    }

    @Schema(name = "MySubscriptionRequest")
    record MySubscriptionRequest(@NotNull UUID locationId, @NotEmpty Set<Channel> channels,
                                 @Size(max = 80) @Schema(example = "location-page") String source) {
    }

    @Schema(name = "ChannelConsent")
    record ConsentResponse(Channel channel, boolean granted, Instant consentedAt, String source,
                           Instant withdrawnAt) {

        static ConsentResponse of(ChannelConsent c) {
            return new ConsentResponse(c.channel(), c.granted(), c.consentedAt(), c.source(), c.withdrawnAt());
        }
    }

    @Schema(name = "Subscription")
    record SubscriptionResponse(UUID id, UUID locationId, String status, List<ConsentResponse> consents,
                                Instant createdAt, Instant confirmedAt) {

        static SubscriptionResponse of(Subscription s) {
            return new SubscriptionResponse(s.id(), s.locationId(), s.status().name(),
                    s.consents().values().stream().sorted((a, b) -> a.channel().compareTo(b.channel()))
                            .map(ConsentResponse::of).toList(),
                    s.createdAt(), s.confirmedAt());
        }
    }
}
