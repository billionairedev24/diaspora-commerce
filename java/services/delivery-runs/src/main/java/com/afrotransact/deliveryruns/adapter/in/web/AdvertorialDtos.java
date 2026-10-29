package com.afrotransact.deliveryruns.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.deliveryruns.application.AdvertorialCommand;
import com.afrotransact.deliveryruns.application.AdvertorialServing.Event;
import com.afrotransact.deliveryruns.application.AdvertorialServing.ServedAdvertorial;
import com.afrotransact.deliveryruns.application.AdvertorialView;
import com.afrotransact.deliveryruns.domain.AdvertorialCopy;
import com.afrotransact.deliveryruns.domain.AdvertorialEnd;
import com.afrotransact.deliveryruns.domain.AdvertorialSource;
import com.afrotransact.deliveryruns.domain.AdvertorialStatus;
import com.afrotransact.deliveryruns.domain.FeaturedProduct;
import com.afrotransact.deliveryruns.domain.LocalAdvertorial;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Wire shapes of the location advertorials (S263). */
final class AdvertorialDtos {

    private AdvertorialDtos() {
    }

    @Schema(name = "FeaturedProduct", description = "A product staff feature on the card (up to four). The price is "
            + "not kept: the product page shows today's.")
    record FeaturedProductBody(@NotBlank @Size(max = 40) @Schema(example = "ATSHEA0001") String atin,
                               @NotBlank @Size(max = 140) String title,
                               @Size(max = 500) @Schema(description = "https picture, if the product has one")
                               String imageUrl) {

        FeaturedProduct toDomain() {
            return new FeaturedProduct(atin, title, imageUrl);
        }

        static FeaturedProductBody of(FeaturedProduct p) {
            return new FeaturedProductBody(p.atin(), p.title(), p.imageUrl());
        }
    }

    @Schema(name = "AdvertorialResults", description = "What an advertorial brought")
    record ResultsResponse(long views, long clicks,
                           @Schema(description = "clicks / views x 100; null without views") Double ctrPct,
                           @Schema(description = "Orders that came through the advertorial and still stand: a "
                                   + "window's pre-orders whose tracking tag carries it, a run's orders whose "
                                   + "attribution (cr=adv-...) does and that still hold a slot on the run") int orders) {

        static ResultsResponse of(AdvertorialView.Results r) {
            return new ResultsResponse(r.views(), r.clicks(), r.ctrPct(), r.orders());
        }
    }

    @Schema(name = "LocalAdvertorial", description = """
            Words for the visitors of the area a pre-order window or run serves, shown with a live closing
            countdown while it takes orders and over when it closes or is cancelled. The ETag carries the version
            for If-Match.""")
    record AdvertorialResponse(UUID id, AdvertorialSource source, UUID sourceId, String sourceName, String sourceCode,
                               String sourceStatus, Instant sourceCloses, UUID locationId, String locationName,
                               String variant, int weight, int priority,
                               @Schema(description = "The words follow the window or run until staff edit them")
                               boolean generated, String headline, String body, String ctaLabel,
                               List<FeaturedProductBody> featuredProducts, Instant startsAt,
                               Instant endsAt, AdvertorialStatus status, AdvertorialEnd ended,
                               @Schema(description = "Visitors can see it right now") boolean serving,
                               ResultsResponse results, String createdBy, Instant createdAt, Instant updatedAt,
                               long version) {

        static AdvertorialResponse of(AdvertorialView v) {
            LocalAdvertorial a = v.advertorial();
            return new AdvertorialResponse(a.id(), a.source(), a.sourceId(), v.sourceName(), v.sourceCode(),
                    v.sourceStatus(), v.sourceCloses(), a.locationId(), v.locationName(), a.variant(), a.weight(),
                    a.priority(), a.generated(), a.copy().headline(), a.copy().body(), a.copy().ctaLabel(),
                    a.featured().stream().map(FeaturedProductBody::of).toList(), a.startsAt(), a.endsAt(), a.status(), a.ended(), v.serving(), ResultsResponse.of(v.results()),
                    a.createdBy(), a.createdAt(), a.updatedAt(), a.version());
        }
    }

    @Schema(name = "LocalAdvertorials")
    record ListResponse(List<AdvertorialResponse> items) {

        static ListResponse of(List<AdvertorialView> views) {
            return new ListResponse(views.stream().map(AdvertorialResponse::of).toList());
        }
    }

    @Schema(name = "GenerateAdvertorialRequest")
    record GenerateRequest(@NotNull AdvertorialSource source, @NotNull UUID sourceId) {
    }

    @Schema(name = "LocalAdvertorialRequest", description = """
            The words, variant, share and schedule staff write. Variant, weight and priority default (the next free
            label, 1, 0). On an edit source and sourceId are ignored.""")
    record AdvertorialRequest(AdvertorialSource source, UUID sourceId,
                              @Size(max = 8) String variant, @Min(1) @Max(LocalAdvertorial.MAX_WEIGHT) Integer weight,
                              @Min(0) @Max(LocalAdvertorial.MAX_PRIORITY) Integer priority,
                              @NotBlank @Size(max = AdvertorialCopy.MAX_HEADLINE) String headline,
                              @Size(max = AdvertorialCopy.MAX_BODY) String body,
                              @NotBlank @Size(max = AdvertorialCopy.MAX_CTA) String ctaLabel, Instant startsAt,
                              Instant endsAt,
                              @Size(max = FeaturedProduct.MAX_PER_ADVERTORIAL) @Valid
                              @Schema(description = "Up to four products to show on the card. Left out on an edit: "
                                      + "the current ones stay; an empty list clears them")
                              List<FeaturedProductBody> featuredProducts) {

        AdvertorialCommand toCommand() {
            return new AdvertorialCommand(source, sourceId, variant, weight, priority, headline, body, ctaLabel,
                    startsAt, endsAt, featuredProducts == null ? null
                            : featuredProducts.stream().map(FeaturedProductBody::toDomain).toList());
        }
    }

    @Schema(name = "ServedAdvertorial", description = "An advertorial ready to show a visitor")
    record ServedResponse(UUID id, AdvertorialSource source, UUID sourceId, String variant,
                          @Schema(description = "Window code; null for runs") String code, String place,
                          String headline, String body, String ctaLabel,
                          @Schema(description = "With UTM and, for windows, order attribution") String ctaUrl,
                          String imageUrl,
                          @Schema(description = "Up to four products staff chose; the card links each to its "
                                  + "product page") List<FeaturedProductBody> featuredProducts,
                          @Schema(description = "The live countdown counts down to this") Instant countdownTarget,
                          Instant expiresAt, String deliveryDisplay, String feeDisplay) {

        static ServedResponse of(ServedAdvertorial s) {
            return new ServedResponse(s.id(), s.source(), s.sourceId(), s.variant(), s.code(), s.place(),
                    s.headline(), s.body(), s.ctaLabel(), s.ctaUrl(), s.imageUrl(),
                    s.featured().stream().map(FeaturedProductBody::of).toList(), s.countdownTarget(),
                    s.expiresAt(), s.deliveryDisplay(), s.feeDisplay());
        }
    }

    @Schema(name = "ServedAdvertorials")
    record ServedListResponse(List<ServedResponse> items) {

        static ServedListResponse of(List<ServedAdvertorial> served) {
            return new ServedListResponse(served.stream().map(ServedResponse::of).toList());
        }
    }

    @Schema(name = "AdvertorialEventRequest")
    record EventRequest(@NotNull @Schema(description = "VIEW: it was shown; CLICK: its button was clicked")
                        Event type,
                        @Size(max = 64) @Schema(description = "The visitor id also sent for sticky variants; one "
                                + "visitor counts once per advertorial and kind in the event window") String visitor) {
    }
}
