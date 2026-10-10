package com.afrotransact.content.adapter.in.web;

import java.util.Locale;

import com.afrotransact.content.application.NewsletterService;
import com.afrotransact.content.domain.newsletter.SubscriptionStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Newsletter", description = "Double opt-in newsletter")
@SecurityRequirements
@RestController
@RequestMapping("/v1/newsletter")
class NewsletterController {

    private final NewsletterService newsletter;

    NewsletterController(NewsletterService newsletter) {
        this.newsletter = newsletter;
    }

    @Schema(name = "NewsletterRequest")
    record SubscribeRequest(String email, @Schema(example = "en") String locale,
                            @Schema(example = "footer") String source) {
    }

    @Schema(name = "NewsletterResponse")
    record StatusResponse(@Schema(allowableValues = {"pending_confirmation", "subscribed", "unsubscribed"}) String status) {

        static StatusResponse of(SubscriptionStatus s) {
            return new StatusResponse(switch (s) {
                case PENDING -> "pending_confirmation";
                case CONFIRMED -> "subscribed";
                case UNSUBSCRIBED -> "unsubscribed";
            });
        }
    }

    @Schema(name = "NewsletterTokenRequest")
    record TokenRequest(@Schema(description = "Signed token from the emailed link") String token) {
    }

    @Operation(summary = "Subscribe: emails a confirmation link (content.newsletter.confirmation_requested)")
    @PostMapping("/subscriptions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    StatusResponse subscribe(@RequestBody SubscribeRequest body) {
        String locale = body.locale() == null ? null : body.locale().toLowerCase(Locale.ROOT);
        return StatusResponse.of(newsletter.subscribe(body.email(), locale, body.source()));
    }

    @Operation(summary = "Confirm with the signed token from the email")
    @PostMapping("/confirmations")
    StatusResponse confirm(@RequestBody TokenRequest body) {
        return StatusResponse.of(newsletter.confirm(body.token()));
    }

    @Operation(summary = "Unsubscribe with the signed token from any newsletter email")
    @PostMapping("/unsubscriptions")
    StatusResponse unsubscribe(@RequestBody TokenRequest body) {
        return StatusResponse.of(newsletter.unsubscribe(body.token()));
    }
}
